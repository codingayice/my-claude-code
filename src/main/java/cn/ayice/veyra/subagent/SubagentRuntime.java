package cn.ayice.veyra.subagent;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.WorkingMessage;
import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.compaction.CompactionService.Trigger;
import cn.ayice.veyra.compaction.SummaryCompactor;
import cn.ayice.veyra.compaction.MicroCompactor;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.compaction.CompactionService;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.subagent.AgentProfile.PermissionPolicy;
import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.tool.state.FileStateCache;
import cn.ayice.veyra.subagent.AgentRunResult;
import cn.ayice.veyra.subagent.SubagentExecution;
import cn.ayice.veyra.tool.ToolService.Authorization;
import cn.ayice.veyra.tool.ToolService;
import cn.ayice.veyra.tool.ToolService.Execution;
import cn.ayice.veyra.tool.ToolExecutionObserver;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 子 agent 和内部 agent 的统一运行时。它按 AgentProfile 构造工具集和权限上下文，然后执行模型到工具再回模型的循环。
 */
public class SubagentRuntime implements SubagentExecution {

    private static final Logger log = LoggerFactory.getLogger(SubagentRuntime.class);
    private static final int MAX_CONSECUTIVE_MODEL_FAILURES = 3;

    private final AIService ai;
    private final AppConfig config;
    private final ToolExecutionConfirmation confirmation;
    private final AgentEventSink eventSink;
    private final PermissionContextStore permissionContextStore;
    private final SubagentToolCatalogFactory toolCatalogFactory;

    /**
     * 使用模型、运行配置、审批和 Boot 提供的工具目录工厂创建子 Agent 运行时。
     */
    public SubagentRuntime(
            AIService ai,
            AppConfig config,
            ToolExecutionConfirmation confirmation,
            AgentEventSink eventSink,
            PermissionContextStore permissionContextStore,
            SubagentToolCatalogFactory toolCatalogFactory
    ) {
        this.ai = ai;
        this.config = config;
        this.confirmation = confirmation;
        this.eventSink = eventSink;
        this.permissionContextStore = permissionContextStore;
        this.toolCatalogFactory = toolCatalogFactory;
    }

    /**
     * 执行当前运行策略并返回最终结果。
     */
    public AgentRunResult run(String prompt, String subagentType, PermissionContext parentPermissionContext) {
        return run(prompt, subagentType, parentPermissionContext, UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * 执行当前运行策略并返回最终结果。
     */
    public AgentRunResult run(String prompt, String subagentType, PermissionContext parentPermissionContext, String agentId) {
        return run(prompt, subagentType, parentPermissionContext, agentId, prompt);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public AgentRunResult run(String prompt, String subagentType, PermissionContext parentPermissionContext,
                              String agentId, String description) {
        return run(
                AgentProfile.fromType(subagentType, config.getMaxRounds()),
                prompt,
                parentPermissionContext,
                agentId,
                description
        );
    }

/**
 * 运行一个 profile 限定的 agent 循环。普通子 agent 和内部 agent 都走这条路径，差异只通过 AgentProfile 表达。
 */
    public AgentRunResult run(AgentProfile profile, String prompt, PermissionContext parentPermissionContext,
                              String agentId, String description) {
        long startedAt = System.currentTimeMillis();
        PermissionPolicy policy = profile.permissionPolicy();
        ToolCatalog toolset = toolCatalogFactory.create(profile);
        ToolService toolEngine = new ToolService(toolset, confirmation, permissionContextStore);

        CompactionConfig runtimeCompactCfg = CompactionConfig.from(config);
        ContextService contextBuilder = new ContextService(
                toolset.specifications(),
                toolset.descriptions(),
                config,
                null,
                runtimeCompactCfg.contextTokenBudget()
        );
        CompactionService turnPreparer = new CompactionService(
                contextBuilder,
                runtimeCompactCfg,
                new MicroCompactor(),
                null,
                new SummaryCompactor(ai),
                toolset.fileStateCache()::recentModifiedPaths
        );
        PermissionContext permissionContext = buildPermissionContext(
                permissionContextStore == null ? parentPermissionContext : permissionContextStore.current(),
                policy
        );

        List<WorkingMessage> messages = new ArrayList<>();
        long nextSequence = 0;
        messages.add(WorkingMessage.original(++nextSequence, SystemMessage.from(profile.systemPrompt())));
        messages.add(WorkingMessage.original(++nextSequence, UserMessage.from(localizedPrompt(prompt))));

        String lastText = null;
        int totalToolUseCount = 0;
        int consecutiveFailures = 0;

        for (int round = 1; profile.maxTurns() <= 0 || round <= profile.maxTurns(); round++) {
            emitTaskEvent(profile, "task.step.started", agentId, description, Map.of("round", round));

            AiMessage aiMessage;
            try {
                CompactionService.PreparedWorkingTurn prepared = turnPreparer.prepareWorking(
                        messages,
                        CompactionService.Trigger.AUTO,
                        round - 1L,
                        permissionContext.workingDir()
                );
                messages = new ArrayList<>(prepared.messages());
                aiMessage = ai.chat(prepared.request()).aiMessage();
                consecutiveFailures = 0;
            } catch (Exception e) {
                log.error("Subagent model call failed agentId={} type={} round={}",
                        agentId, profile.type(), round, e);
                consecutiveFailures++;
                if (consecutiveFailures >= MAX_CONSECUTIVE_MODEL_FAILURES) {
                    AgentRunResult failed = result(
                            agentId,
                            profile,
                            "error",
                            partialOrError(lastText, "LLM 连续失败次数过多: " + e.getMessage()),
                            startedAt,
                            totalToolUseCount
                    );
                    emitTaskEvent(profile, "task.failed", agentId, description,
                            Map.of("content", failed.content(), "error", e.getMessage()));
                    return failed;
                }
                messages.add(WorkingMessage.original(
                        ++nextSequence,
                        UserMessage.from("<error>LLM 调用失败: " + e.getMessage() + "。请重试或更换方法。</error>")
                ));
                continue;
            }

            if (aiMessage.text() != null && !aiMessage.text().isBlank()) {
                lastText = aiMessage.text();
            }

            messages.add(WorkingMessage.original(++nextSequence, aiMessage));
            emitTaskEvent(profile, "task.assistant.message.completed", agentId, description,
                    Map.of("text", aiMessage.text() == null ? "" : aiMessage.text(),
                            "hasToolRequests", aiMessage.hasToolExecutionRequests()));
            if (!aiMessage.hasToolExecutionRequests()) {
                AgentRunResult completed = result(agentId, profile, "completed", aiMessage.text(), startedAt, totalToolUseCount);
                emitTaskEvent(profile, "task.completed", agentId, description,
                        Map.of("content", completed.content(),
                                "totalDurationMs", completed.totalDurationMs(),
                                "totalToolUseCount", completed.totalToolUseCount()));
                return completed;
            }

            for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                totalToolUseCount++;
                emitTaskEvent(profile, "task.tool.call.started", agentId, description,
                        Map.of("toolUseId", request.id(), "name", request.name(), "arguments", request.arguments()));
                Authorization authorization = toolEngine.authorize(
                        request,
                        permissionContext,
                        policy,
                        new ToolExecutionObserver() {
                            /**
                             * {@inheritDoc}
                             */
                            @Override
                            public void permissionRequested(
                                    ToolExecutionRequest toolRequest,
                                    PermissionDecision decision
                            ) {
                                emitTaskEvent(profile, "task.permission.requested", agentId, description,
                                        Map.of(
                                                "toolUseId", toolRequest.id(),
                                                "name", toolRequest.name(),
                                                "arguments", toolRequest.arguments(),
                                                "reason", decision.reason()
                                        ));
                            }

                            /**
                             * {@inheritDoc}
                             */
                            @Override
                            public void permissionResolved(
                                    ToolExecutionRequest toolRequest,
                                    ToolExecutionConfirmation.Choice choice
                            ) {
                                emitTaskEvent(profile, "task.permission.resolved", agentId, description,
                                        Map.of(
                                                "toolUseId", toolRequest.id(),
                                                "name", toolRequest.name(),
                                                "decision", choice.name().toLowerCase()
                                        ));
                            }
                        }
                );
                permissionContext = authorization.context();
                if (!authorization.allowed()) {
                    emitTaskEvent(profile, "task.tool.call.rejected", agentId, description,
                            Map.of(
                                    "toolUseId", request.id(),
                                    "name", request.name(),
                                    "reason", authorization.rejectionReason()
                            ));
                    messages.add(WorkingMessage.original(
                            ++nextSequence,
                            ToolExecutionResultMessage.from(
                                    request,
                                    "<rejected>" + authorization.rejectionReason() + "</rejected>"
                            )
                    ));
                    continue;
                }

                Execution execution = toolEngine.execute(authorization, permissionContext, policy);
                String content = execution.content();
                emitTaskEvent(profile, "task.tool.call.completed", agentId, description,
                        Map.of(
                                "toolUseId", request.id(),
                                "name", request.name(),
                                "success", execution.result().success(),
                                "content", content
                        ));
                messages.add(WorkingMessage.original(
                        ++nextSequence,
                        ToolExecutionResultMessage.from(request, content)
                ));
            }
        }

        AgentRunResult maxTurns = result(
                agentId,
                profile,
                "max_turns",
                partialOrError(lastText, "子 agent 已达到最大轮数，但没有给出最终回答。"),
                startedAt,
                totalToolUseCount
        );
        emitTaskEvent(profile, "task.failed", agentId, description,
                Map.of("content", maxTurns.content(), "reason", "max_turns"));
        return maxTurns;
    }

/**
 * 先构造本地全部工具，再用 profile 同时过滤 registry 和 dispatcher。这样模型可见的 schema 与实际可执行工具不会分叉。
 */
    /**
     * 根据当前输入构建权限上下文。
     */
    private PermissionContext buildPermissionContext(PermissionContext parent, PermissionPolicy policy) {
        Path workingDir = parent == null || parent.workingDir() == null
                ? Path.of(config.getWorkspace())
                : parent.workingDir();
        PermissionMode inheritedMode = parent == null || parent.mode() == null ? PermissionMode.ASK_EVERY_TIME : parent.mode();
        PermissionMode mode = policy.permissionModeOverride() == null ? inheritedMode : policy.permissionModeOverride();
        PermissionContext.Builder builder = PermissionContext.builder()
                .workingDir(workingDir)
                .mode(mode);
        if (parent != null) {
            for (Path directory : parent.allowedDirectories()) {
                builder.addAllowedDirectory(directory);
            }
            parent.rules().forEach(builder::addRule);
        } else {
            builder.addAllowedDirectory(workingDir);
        }
        return builder.build();
    }

    /**
     * 将子 Agent 终态、输出和错误封装为统一运行结果。
     */
    private AgentRunResult result(String agentId, AgentProfile profile, String status,
                                  String content, long startedAt, int totalToolUseCount) {
        return new AgentRunResult(
                agentId,
                profile.type(),
                status,
                content == null ? "" : content,
                System.currentTimeMillis() - startedAt,
                totalToolUseCount
        );
    }

    /**
     * 优先返回已生成的部分输出；没有输出时返回安全错误摘要。
     */
    private String partialOrError(String partial, String error) {
        if (partial != null && !partial.isBlank()) {
            return "[部分结果]\n" + partial + "\n\n[已停止] " + error;
        }
        return "[已停止] " + error;
    }

    /**
     * 为子 Agent 提示词补充与主会话一致的语言要求。
     */
    private String localizedPrompt(String prompt) {
        return """
                请执行下面的子 agent 任务。
                语言要求:
                - 最终回答必须使用中文。
                - 过程中的自然语言说明、总结、风险和结论都必须使用中文。
                - 代码、命令、文件路径、API 名称、类型名、错误日志原文和 VERDICT 这类固定标记可以保持原文。
                - 如果任务说明本身是英文，也不要用英文作答，除非是在引用原文。
                任务:
                %s
                """.formatted(prompt == null ? "" : prompt);
    }

    /**
     * 处理并传播 {@code emitTaskEvent} 对应的事件。
     */
    private void emitTaskEvent(AgentProfile profile, String type, String agentId, String description, Map<String, Object> extra) {
        if (eventSink == null || profile == null || !profile.recordTranscript()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", agentId);
        payload.put("taskType", "subagent");
        payload.put("subagentType", profile.type());
        payload.put("description", description == null ? "" : description);
        payload.putAll(extra);
        eventSink.emit(type, payload);
    }

}
