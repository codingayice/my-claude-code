package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.session.persistence.TranscriptRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;
import cn.ayice.veyra.runtime.MemoryExtractionCoordinator;
import cn.ayice.veyra.tool.ToolService;
import cn.ayice.veyra.tool.ToolService.Authorization;
import cn.ayice.veyra.tool.ToolService.Execution;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import cn.ayice.veyra.tool.ToolExecutionObserver;
import cn.ayice.veyra.tool.ToolExecutionPolicy;
import cn.ayice.veyra.tool.ToolResult;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 主 Agent 单轮工具调用协调器。
 * <p>它先完成整批授权，再并行执行允许的工具，最后按模型请求顺序收集结果并形成下一轮上下文。</p>
 */
final class AgentToolCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AgentToolCoordinator.class);
    private static final ToolExecutionPolicy MAIN_TOOL_POLICY = ToolExecutionPolicy.mainAgent();

    private final ToolService toolEngine;
    private final PermissionContextStore permissionContextStore;
    private final AgentLoopEvents events;
    private final TranscriptRecorder transcriptRecorder;
    private final MemoryExtractionCoordinator memoryExtractionCoordinator;
    private final Executor toolExecutor;

    /**
     * 使用统一工具引擎、会话权限状态和事件输出组件创建协调器。
     */
    AgentToolCoordinator(
            ToolService toolEngine,
            PermissionContextStore permissionContextStore,
            AgentLoopEvents events,
            TranscriptRecorder transcriptRecorder,
            MemoryExtractionCoordinator memoryExtractionCoordinator,
            Executor toolExecutor
    ) {
        this.toolEngine = toolEngine;
        this.permissionContextStore = permissionContextStore;
        this.events = events;
        this.transcriptRecorder = transcriptRecorder;
        this.memoryExtractionCoordinator = memoryExtractionCoordinator;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 处理当前模型响应中的全部工具请求，并在所有工具结束后返回更新后的消息上下文。
     */
    Result execute(List<ChatMessage> initialMessages, List<ToolExecutionRequest> requests) {
        List<ChatMessage> messages = initialMessages;
        PermissionContext permissionContext = permissionContextStore.current();
        List<Authorization> approvedCalls = new ArrayList<>();

        // 授权阶段保持模型请求顺序；拒绝结果也必须写回上下文，避免模型等待不存在的 tool result。
        for (ToolExecutionRequest request : requests) {
            permissionContext = permissionContextStore.current();
            Authorization authorization = toolEngine.authorize(
                    request,
                    permissionContext,
                    MAIN_TOOL_POLICY,
                    new ToolExecutionObserver() {
                        /**
                         * {@inheritDoc}
                         */
                        @Override
                        public void authorizationDecided(
                                ToolExecutionRequest toolRequest,
                                PermissionDecision decision
                        ) {
                            log.info("   [工具]{}:{}", toolRequest.name(), decision.kind());
                            events.toolStarted(toolRequest);
                        }
                    }
            );
            permissionContext = authorization.context();

            if (!authorization.allowed()) {
                if (authorization.choice() == ToolExecutionConfirmation.Choice.DENY) {
                    log.info("   [工具]用户拒绝了工具调用");
                }
                ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(
                        request,
                        "<rejected>" + authorization.rejectionReason() + "</rejected>"
                );
                transcriptRecorder.record(resultMessage);
                messages = append(messages, resultMessage);
                events.toolRejected(request, authorization.rejectionReason());
                continue;
            }

            if (authorization.decision().kind() == PermissionDecision.Kind.ASK) {
                if (authorization.choice() == ToolExecutionConfirmation.Choice.ALLOW_FOR_SESSION) {
                    log.info("   [工具]用户允许会话内该工具调用");
                }
                log.info("   [工具]用户允许了本次工具调用");
            }
            approvedCalls.add(authorization);
        }

        boolean todoWriteUsed = false;
        boolean memoryWritten = false;
        if (!approvedCalls.isEmpty()) {
            PermissionContext executionContext = permissionContext;
            List<CompletableFuture<Execution>> futures = new ArrayList<>();
            // 同一轮中互不依赖的工具并行启动，以最长工具耗时作为整批耗时上界。
            for (Authorization authorization : approvedCalls) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> executePersisted(authorization, executionContext),
                        toolExecutor
                ));
            }

            // Future#get 构成轮次屏障：全部结果按请求顺序写回后，AgentLoop 才能进入下一轮模型调用。
            for (int index = 0; index < futures.size(); index++) {
                ToolExecutionRequest request = approvedCalls.get(index).request();
                Execution execution;
                try {
                    execution = futures.get(index).get();
                } catch (Exception error) {
                    // 单个工具失败被规范化为 tool result，不中断同一批其他工具的结果收集。
                    log.error("工具执行失败, toolUseId={}, name={}", request.id(), request.name(), error);
                    ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(
                            request,
                            "<error>工具执行失败: " + error.getMessage() + "</error>"
                    );
                    transcriptRecorder.record(resultMessage);
                    messages = append(messages, resultMessage);
                    events.toolFailed(request, error);
                    continue;
                }
                ToolResult result = execution.result();
                String content = execution.content();
                ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(request, content);
                transcriptRecorder.record(resultMessage);
                messages = append(messages, resultMessage);
                events.toolCompleted(request, result, content);
                todoWriteUsed |= "TodoWrite".equals(request.name());
                memoryWritten |= result.success()
                        && memoryExtractionCoordinator != null
                        && memoryExtractionCoordinator.isMemoryWriteRequest(request);
            }
        }

        return new Result(messages, permissionContext, todoWriteUsed, memoryWritten);
    }

    /**
     * 将 durable started 事实紧邻真实工具调用写入，恢复时据此区分 NOT_EXECUTED 与 UNKNOWN。
     */
    private Execution executePersisted(Authorization authorization, PermissionContext executionContext) {
        if (transcriptRecorder instanceof SessionJournalRecorder journal) {
            journal.recordToolStarted(authorization.request().id(), authorization.request().name());
        }
        return toolEngine.execute(authorization, executionContext, MAIN_TOOL_POLICY);
    }

    /**
     * 复制消息列表并追加一条工具结果，避免原地修改调用方持有的上下文。
     */
    private static List<ChatMessage> append(List<ChatMessage> messages, ChatMessage message) {
        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        return next;
    }

    /**
     * Result 表示操作执行结果。
     */
    record Result(
            List<ChatMessage> messages,
            PermissionContext permissionContext,
            boolean todoWriteUsed,
            boolean memoryWritten
    ) {
    }
}
