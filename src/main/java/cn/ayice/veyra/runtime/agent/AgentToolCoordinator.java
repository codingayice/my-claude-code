package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.session.persistence.JournalMessageRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.runtime.MemoryExtractionCoordinator;
import cn.ayice.veyra.tool.ToolService;
import cn.ayice.veyra.tool.ToolService.Authorization;
import cn.ayice.veyra.tool.ToolService.Execution;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final JournalMessageRecorder messageRecorder;
    private final MemoryExtractionCoordinator memoryExtractionCoordinator;
    private final Executor toolExecutor;

    /**
     * 使用统一工具引擎、会话权限状态和事件输出组件创建协调器。
     */
    AgentToolCoordinator(
            ToolService toolEngine,
            PermissionContextStore permissionContextStore,
            AgentLoopEvents events,
            JournalMessageRecorder messageRecorder,
            MemoryExtractionCoordinator memoryExtractionCoordinator,
            Executor toolExecutor
    ) {
        this.toolEngine = toolEngine;
        this.permissionContextStore = permissionContextStore;
        this.events = events;
        this.messageRecorder = messageRecorder;
        this.memoryExtractionCoordinator = memoryExtractionCoordinator;
        this.toolExecutor = toolExecutor;
    }

    /**
     * 处理当前模型响应中的全部工具请求，并在所有工具结束后返回更新后的消息上下文。
     */
    Result execute(List<ChatMessage> initialMessages, List<ToolExecutionRequest> requests) {
        return execute(initialMessages, requests, Map.of());
    }

    /** 从持久化审批状态重新推进同一个工具批次。 */
    Result resume(List<ChatMessage> initialMessages, List<ToolExecutionRequest> requests,
                  Map<String, cn.ayice.veyra.session.PendingApprovalState> approvals) {
        return execute(initialMessages, requests, approvals);
    }

    /** 计算完整授权批次，并在无未决审批时执行或拒绝每个 ToolUse。 */
    private Result execute(List<ChatMessage> initialMessages, List<ToolExecutionRequest> requests,
                           Map<String, cn.ayice.veyra.session.PendingApprovalState> approvals) {
        List<ChatMessage> messages = initialMessages;
        PermissionContext permissionContext = permissionContextStore.current();
        List<Authorization> approvedCalls = new ArrayList<>();
        List<Authorization> rejectedCalls = new ArrayList<>();
        List<String> pendingApprovalIds = new ArrayList<>();
        Map<String, cn.ayice.veyra.session.PendingApprovalState> approvalsByTool = new LinkedHashMap<>();
        approvals.values().forEach(approval -> approvalsByTool.put(approval.toolUseId(), approval));

        // 授权阶段保持模型请求顺序；拒绝结果也必须写回上下文，避免模型等待不存在的 tool result。
        for (ToolExecutionRequest request : requests) {
            permissionContext = permissionContextStore.current();
            cn.ayice.veyra.session.PendingApprovalState persisted = approvalsByTool.get(request.id());
            Authorization authorization = persisted != null
                    ? persisted.status() == cn.ayice.veyra.session.PendingApprovalState.ApprovalStatus.PENDING
                    ? null : toolEngine.resolve(request, persisted.decision(), permissionContext)
                    : toolEngine.authorize(request, permissionContext, MAIN_TOOL_POLICY, new ToolExecutionObserver() {
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
                    });
            if (authorization == null) {
                pendingApprovalIds.add(persisted.approvalId());
                continue;
            }
            permissionContext = authorization.context();

            if (authorization.approvalRequired()) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("approvalId", authorization.approvalId());
                payload.put("toolUseId", request.id());
                payload.put("tool", request.name());
                payload.put("arguments", request.arguments() == null ? "" : request.arguments());
                payload.put("reason", authorization.decision().reason());
                if (messageRecorder instanceof SessionJournalRecorder journal) {
                    journal.recordDomainEvent(SessionJournalTypes.PERMISSION_REQUESTED, payload);
                }
                events.approvalRequested(payload);
                pendingApprovalIds.add(authorization.approvalId());
                continue;
            }

            if (!authorization.allowed()) {
                rejectedCalls.add(authorization);
                continue;
            }
            approvedCalls.add(authorization);
        }

        if (!pendingApprovalIds.isEmpty()) {
            return new Result(messages, permissionContext, false, false, true, pendingApprovalIds);
        }

        boolean todoWriteUsed = false;
        boolean memoryWritten = false;
        Map<String, Authorization> rejectedById = new LinkedHashMap<>();
        rejectedCalls.forEach(item -> rejectedById.put(item.request().id(), item));
        Map<String, CompletableFuture<Execution>> executionsById = new LinkedHashMap<>();
        if (!approvedCalls.isEmpty()) {
            PermissionContext executionContext = permissionContext;
            // 同一轮中互不依赖的工具并行启动，以最长工具耗时作为整批耗时上界。
            for (Authorization authorization : approvedCalls) {
                executionsById.put(authorization.request().id(), CompletableFuture.supplyAsync(
                        () -> executePersisted(authorization, executionContext),
                        toolExecutor));
            }
        }

        // 工具可以并行执行，但结果事实和模型上下文严格遵循声明顺序。
        for (ToolExecutionRequest request : requests) {
            Authorization rejected = rejectedById.get(request.id());
            if (rejected != null) {
                ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(
                        request, "<rejected>" + rejected.rejectionReason() + "</rejected>");
                messageRecorder.record(resultMessage);
                messages = append(messages, resultMessage);
                events.toolRejected(request, rejected.rejectionReason());
                continue;
            }
            CompletableFuture<Execution> future = executionsById.get(request.id());
            if (future == null) continue;
            Execution execution;
            try {
                execution = future.get();
            } catch (Exception error) {
                log.error("工具执行失败, toolUseId={}, name={}", request.id(), request.name(), error);
                ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(
                        request, "<error>工具执行失败: " + error.getMessage() + "</error>");
                messageRecorder.record(resultMessage);
                messages = append(messages, resultMessage);
                events.toolFailed(request, error);
                continue;
            }
            ToolResult result = execution.result();
            String content = execution.content();
            ToolExecutionResultMessage resultMessage = ToolExecutionResultMessage.from(request, content);
            messageRecorder.record(resultMessage);
            messages = append(messages, resultMessage);
            events.toolCompleted(request, result, content);
            todoWriteUsed |= "TodoWrite".equals(request.name());
            memoryWritten |= result.success() && memoryExtractionCoordinator != null
                    && memoryExtractionCoordinator.isMemoryWriteRequest(request);
        }

        return new Result(messages, permissionContext, todoWriteUsed, memoryWritten, false, List.of());
    }

    /**
     * 将 durable started 事实紧邻真实工具调用写入，恢复时据此区分 NOT_EXECUTED 与 UNKNOWN。
     */
    private Execution executePersisted(Authorization authorization, PermissionContext executionContext) {
        if (messageRecorder instanceof SessionJournalRecorder journal) {
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
            boolean memoryWritten,
            boolean suspended,
            List<String> pendingApprovalIds
    ) {
        Result { pendingApprovalIds = List.copyOf(pendingApprovalIds); }
    }
}
