package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.context.WorkingMessage;
import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.compaction.CompactBoundary;
import cn.ayice.veyra.compaction.CompactionService.Strategy;
import cn.ayice.veyra.compaction.CompactionService.Trigger;
import cn.ayice.veyra.compaction.CompactionService;
import cn.ayice.veyra.compaction.SummaryCompactor;
import cn.ayice.veyra.compaction.MicroCompactor;
import cn.ayice.veyra.compaction.SessionSummaryState;
import cn.ayice.veyra.compaction.BackgroundSummaryScheduler;
import cn.ayice.veyra.session.persistence.JournalMessageRecorder;
import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.runtime.MemoryExtractionCoordinator;
import cn.ayice.veyra.runtime.PendingInputQueue;
import cn.ayice.veyra.runtime.model.ModelCallExecutor;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.tool.ToolService;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.state.FileStateCache;
import cn.ayice.veyra.tool.state.TodoManager;
import cn.ayice.veyra.subagent.SubagentService;
import cn.ayice.veyra.tool.background.BackgroundManager;
import cn.ayice.veyra.tool.background.TaskNotification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import cn.ayice.veyra.session.state.AgentPhase;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import java.util.concurrent.Executor;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 主 Agent 对话循环。它在 process 之间持有唯一 Working History，并按轮执行模型与完整工具批次。
 */
public class AgentLoop {

    private static final int TODO_REMINDER_GRACE_ROUNDS = 3;
    private static final int MODEL_FAILURE_LIMIT = 3;
    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    private final CompactionService turnPreparer;
    private final ContextService contextBuilder;
    private final AIService ai;
    private final AgentToolCoordinator toolCoordinator;
    private final PermissionContextStore permissionContextStore;
    private final TodoManager todoManager;
    private final BackgroundManager bgManager;
    private final SubagentService subagentService;
    private final AgentLoopEvents events;
    private final CompactionConfig compactConfig;
    private final int maxRounds;
    private final BackgroundSummaryScheduler sessionSummaryCoordinator;
    private final SessionSummaryState summaryState;
    private final MemoryExtractionCoordinator memoryExtractionCoordinator;
    private final long modelCallTimeoutMs;
    private final JournalMessageRecorder messageRecorder;
    private final PendingInputQueue pendingInputs = new PendingInputQueue();

    private PermissionContext permissionContext;
    private List<WorkingMessage> history;
    private long nextSequence;
    private long completedModelRounds;
    private boolean todoWriteUsed;

    public AgentLoop(
            AIService ai,
            ToolCatalog tools,
            ContextService contextBuilder,
            BackgroundManager bgManager,
            ToolExecutionConfirmation confirmation,
            PermissionContextStore permissionContextStore,
            TodoManager todoManager,
            CompactionConfig compactConfig,
            int maxRounds,
            SubagentService subagentService,
            AgentEventSink eventSink,
            SessionSummaryState summaryState,
            BackgroundSummaryScheduler sessionSummaryCoordinator,
            FileStateCache fileStateCache,
            long modelCallTimeoutMs,
            MemoryExtractionCoordinator memoryExtractionCoordinator,
            List<ChatMessage> initialHistory,
            JournalMessageRecorder messageRecorder,
            Executor toolExecutor
    ) {
        if (modelCallTimeoutMs <= 0) {
            throw new IllegalArgumentException("modelCallTimeoutMs must be positive");
        }
        this.compactConfig = compactConfig;
        this.contextBuilder = contextBuilder;
        this.summaryState = summaryState;
        this.sessionSummaryCoordinator = sessionSummaryCoordinator;
        this.turnPreparer = new CompactionService(
                contextBuilder,
                compactConfig,
                new MicroCompactor(),
                summaryState,
                new SummaryCompactor(ai),
                fileStateCache::recentModifiedPaths
        );
        this.ai = ai;
        ToolService toolEngine = new ToolService(tools, confirmation, permissionContextStore);
        this.permissionContextStore = permissionContextStore;
        this.permissionContext = permissionContextStore.current();
        this.todoManager = todoManager;
        this.bgManager = bgManager;
        this.subagentService = subagentService;
        this.events = new AgentLoopEvents(eventSink);
        this.maxRounds = maxRounds;
        this.memoryExtractionCoordinator = memoryExtractionCoordinator;
        this.modelCallTimeoutMs = modelCallTimeoutMs;
        this.messageRecorder = messageRecorder;
        this.history = new ArrayList<>(initialHistory.size());
        for (ChatMessage message : initialHistory) {
            this.history.add(WorkingMessage.original(++nextSequence, message));
        }
        this.toolCoordinator = new AgentToolCoordinator(
                toolEngine,
                permissionContextStore,
                this.events,
                messageRecorder,
                memoryExtractionCoordinator,
                toolExecutor
        );
    }

    /**
     * 保留会话启动钩子；当前初始化已在构造和 SessionRuntimeFactory 中完成。
     */
    public void init() {
        // reserved for future lifecycle hooks
    }

    /**
     * 关闭当前会话的后台摘要状态和子 Agent 任务。
     */
    public void shutdown() {
        if (sessionSummaryCoordinator != null) {
            sessionSummaryCoordinator.close();
        }
        if (summaryState != null) {
            summaryState.close();
        }
        if (subagentService != null) {
            subagentService.shutdown();
        }
    }

    /**
     * 处理一条用户输入，串行推进模型轮次，并在每个工具批次全部汇合后继续下一轮。
     *
     * @param input 当前用户提交的文本
     * @return 最终助手回复或终止流程的错误文本
     */
    public synchronized String process(String input) {
        events.userMessage(input);
        permissionContext = permissionContextStore.current();

        LoopState state = LoopState.initial(history, nextSequence, nextSequence)
                .appendOriginal(UserMessage.from(input))
                .markStable();
        contextBuilder.prefetchMemory(input);
        messageRecorder.record(UserMessage.from(input));
        boolean promptTooLongCompactionAttempted = false;
        boolean mainAgentWroteMemory = false;

        while (true) {
            if (!state.isTerminal()) {
                List<PendingInputQueue.Message> steeringMessages = pendingInputs.drainSteers();
                for (PendingInputQueue.Message steering : steeringMessages) {
                    UserMessage steeringMessage = UserMessage.from(steering.text());
                    state = state.appendOriginal(steeringMessage).markStable();
                    messageRecorder.record(steeringMessage);
                    events.pendingInputApplied(steering.id(), "steer");
                    events.steeringUserMessage(steering.text(), steering.id());
                }
            }
            if (state.isTerminal()) {
                history = state.messages();
                nextSequence = state.nextSequence();
                String terminal = terminalMessage(state);
                events.runCompleted(state.phase().name(), terminal);
                return terminal;
            }

            List<TaskNotification> notifications = drainTaskNotifications();
            if (!notifications.isEmpty()) {
                UserMessage notificationMessage = UserMessage.from(
                        formatNotificationBlock("task_notifications", notifications)
                );
                state = state.appendOriginal(notificationMessage).markStable();
                messageRecorder.record(notificationMessage);
            }

            CompactionService.PreparedWorkingTurn prepared;
            long prepareStartedAt = System.currentTimeMillis();
            try {
                prepared = turnPreparer.prepareWorking(
                        state.messages(),
                        CompactionService.Trigger.AUTO,
                        completedModelRounds,
                        permissionContext.workingDir()
                );
            } catch (CompactionService.PreparationException preparationFailure) {
                events.compactionFailed(
                        CompactionService.Trigger.AUTO,
                        preparationFailure.errorCode(),
                        System.currentTimeMillis() - prepareStartedAt
                );
                history = state.messages();
                nextSequence = state.nextSequence();
                if ("COMPACTION_INSUFFICIENT".equals(preparationFailure.errorCode())) {
                    String terminal = "<error>上下文已接近模型上限，自动压缩未能将上下文降到安全范围。请先压缩上下文后再继续。</error>";
                    events.runCompleted("blocking_limit", terminal);
                    return terminal;
                }
                String terminal = "<error>上下文压缩失败，已阻止本次模型调用。错误码: "
                        + preparationFailure.errorCode() + "</error>";
                events.runFailed("context_preparation_failed", terminal);
                return terminal;
            }
            state = state.withMessages(prepared.messages())
                    .withCapacityState(prepared.capacityState())
                    .withRequest(prepared.request())
                    .markStable();
            events.contextUsage(prepared, compactConfig);
            if (prepared.strategy() != CompactionService.Strategy.NONE) {
                events.compactionCompleted(
                        CompactionService.Trigger.AUTO,
                        prepared,
                        System.currentTimeMillis() - prepareStartedAt
                );
            }
            if (sessionSummaryCoordinator != null) {
                sessionSummaryCoordinator.onPreparedCapacity(state.stableSnapshot(), prepared.capacityState());
            }
            events.contextWarning(compactConfig.evaluate(prepared.inputTokens()), compactConfig, "request");

            ChatRequest request = prepared.request();
            AiMessage aiMessage;
            String modelCallId = java.util.UUID.randomUUID().toString();
            recordDomain(SessionJournalTypes.MODEL_CALL_STARTED, Map.of(
                    "modelCallId", modelCallId,
                    "round", state.turnCount()
            ));
            try {
                CompletableFuture<AiMessage> future = ai.streamingChat(
                        request.messages(),
                        request.toolSpecifications(),
                        events::assistantToken
                );
                aiMessage = ModelCallExecutor.await(future, modelCallTimeoutMs);
            } catch (Exception modelFailure) {
                recordDomain(SessionJournalTypes.MODEL_CALL_FAILED, Map.of(
                        "modelCallId", modelCallId,
                        "errorCode", "MODEL_CALL_FAILED",
                        "retryable", true,
                        "round", state.turnCount()
                ));
                if (!promptTooLongCompactionAttempted && isPromptTooLong(modelFailure)) {
                    promptTooLongCompactionAttempted = true;
                    long reactiveStartedAt = System.currentTimeMillis();
                    try {
                        CompactionService.PreparedWorkingTurn reactive = turnPreparer.prepareWorking(
                                state.messages(),
                                CompactionService.Trigger.REACTIVE,
                                completedModelRounds,
                                permissionContext.workingDir()
                        );
                        state = state.withMessages(reactive.messages())
                                .withCapacityState(reactive.capacityState())
                                .withRequest(null)
                                .withPhase(AgentPhase.READY_FOR_MODEL, "prompt_too_long_compacted")
                                .markStable();
                        events.contextUsage(reactive, compactConfig);
                        if (reactive.strategy() != CompactionService.Strategy.NONE) {
                            events.compactionCompleted(
                                    CompactionService.Trigger.REACTIVE,
                                    reactive,
                                    System.currentTimeMillis() - reactiveStartedAt
                            );
                        }
                        continue;
                    } catch (CompactionService.PreparationException reactiveFailure) {
                        events.compactionFailed(
                                CompactionService.Trigger.REACTIVE,
                                reactiveFailure.errorCode(),
                                System.currentTimeMillis() - reactiveStartedAt
                        );
                        logger.error("响应式上下文压缩失败: {}", reactiveFailure.errorCode(), modelFailure);
                    }
                }

                String errorMessage = "<error>LLM 调用失败: "
                        + ModelCallExecutor.safeErrorMessage(modelFailure) + "。请重试。</error>";
                int nextFailureCount = state.failureCount() + 1;
                logger.error("[LLM]第{}次调用失败", nextFailureCount, modelFailure);
                if (nextFailureCount >= MODEL_FAILURE_LIMIT) {
                    events.runFailed("model_call_failed", errorMessage);
                    state = state.withFailureCount(nextFailureCount)
                            .withAiMessage(null)
                            .withApprovedRequests(null)
                            .withRequest(null)
                            .withTerminalPhase(AgentPhase.TERMINAL_FAILED, "model_call_failed");
                    continue;
                }
                state = state.withFailureCount(nextFailureCount)
                        .withAiMessage(null)
                        .withApprovedRequests(null)
                        .withRequest(null)
                        .withPhase(AgentPhase.READY_FOR_MODEL, "model_call_failed");
                continue;
            }

            state = state.appendOriginal(aiMessage);
            completedModelRounds++;
            messageRecorder.record(aiMessage);
            events.assistantCompleted(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                state = state.markStable();
                submitStableSnapshot(state);
                fireLongTermMemoryExtraction(state, mainAgentWroteMemory);
                state = state.withAiMessage(aiMessage)
                        .withRequest(request)
                        .withFailureCount(0)
                        .withTurnCount(state.turnCount() + 1)
                        .withApprovedRequests(null)
                        .withTerminalPhase(AgentPhase.TERMINAL_COMPLETED, "completed");
                continue;
            }

            List<ChatMessage> beforeTools = state.chatMessages();
            AgentToolCoordinator.Result toolResult = toolCoordinator.execute(
                    beforeTools,
                    aiMessage.toolExecutionRequests()
            );
            if (toolResult.messages().size() < beforeTools.size()) {
                throw new IllegalStateException("tool coordinator removed existing history");
            }
            for (ChatMessage toolMessage : toolResult.messages().subList(beforeTools.size(), toolResult.messages().size())) {
                state = state.appendOriginal(toolMessage);
            }
            permissionContext = toolResult.permissionContext();
            todoWriteUsed |= toolResult.todoWriteUsed();
            mainAgentWroteMemory |= toolResult.memoryWritten();
            state = state.markStable();
            submitStableSnapshot(state);

            int nextRound = state.turnCount() + 1;
            if (todoManager != null && todoManager.hasOpenItems()
                    && nextRound % TODO_REMINDER_GRACE_ROUNDS == 0) {
                UserMessage reminder = UserMessage.from(
                        "<system-reminder>Todo 列表仍有未完成项。请继续处理或关闭这些事项后再进入下一步。</system-reminder>"
                );
                state = state.appendOriginal(reminder).markStable();
                messageRecorder.record(reminder);
            }
            if (!todoWriteUsed && nextRound >= TODO_REMINDER_GRACE_ROUNDS
                    && nextRound % TODO_REMINDER_GRACE_ROUNDS == 0
                    && todoManager != null && !todoManager.hasOpenItems()) {
                UserMessage reminder = UserMessage.from(
                        "<system-reminder>你还没有使用 TodoWrite 工具规划任务。如果当前任务涉及 3 个以上独立步骤，请先用 TodoWrite 创建任务清单，再逐步执行。</system-reminder>"
                );
                state = state.appendOriginal(reminder).markStable();
                messageRecorder.record(reminder);
            }
            if (maxRounds > 0 && nextRound > maxRounds) {
                state = state.withAiMessage(aiMessage)
                        .withRequest(request)
                        .withApprovedRequests(null)
                        .withTerminalPhase(AgentPhase.TERMINAL_MAX_ROUNDS, "max_turns");
                continue;
            }
            state = state.withAiMessage(aiMessage)
                    .withRequest(request)
                    .withApprovedRequests(null)
                    .withFailureCount(0)
                    .withTurnCount(nextRound)
                    .withPhase(AgentPhase.READY_FOR_MODEL, "next_turn");
        }
    }

    /** 从 Agent 状态机动作边界追加稳定领域事件。 */
    private void recordDomain(String type, Map<String, Object> payload) {
        if (messageRecorder instanceof SessionJournalRecorder recorder) {
            recorder.recordDomainEvent(type, payload);
        }
    }

    /**
     * 在当前历史不存在执行中工具批次时，把不可变稳定快照提交给后台摘要协调器。
     */
    private void submitStableSnapshot(LoopState state) {
        if (sessionSummaryCoordinator != null) {
            sessionSummaryCoordinator.submitStableSnapshot(state.stableSnapshot());
        }
    }

    /**
     * 将循环终态转换为返回给当前调用方的稳定文本。
     */
    private String terminalMessage(LoopState state) {
        return switch (state.phase()) {
            case TERMINAL_COMPLETED -> state.aiMessage() != null ? state.aiMessage().text() : "";
            case TERMINAL_MAX_ROUNDS ->
                    "<error>已达到最大轮数 (" + maxRounds + ")，任务仍未完成。对话结束。</error>";
            case TERMINAL_FAILED -> "<error>LLM 调用连续失败次数过多。对话结束。</error>";
            case TERMINAL_CANCELLED -> "<error>对话已取消。</error>";
            default -> state.aiMessage() != null ? state.aiMessage().text() : "";
        };
    }

    /**
     * 返回去除 sequence 包装后的当前 Agent 历史副本。
     */
    public List<ChatMessage> getHistory() {
        return WorkingMessage.unwrap(history);
    }

    /** 返回当前 Session 与主循环共享的运行中输入队列。 */
    public PendingInputQueue pendingInputs() {
        return pendingInputs;
    }

    /** 恢复 SessionState 中尚未消费的 Pending Input。 */
    public void restorePendingInputs(List<Map<String, Object>> persisted) {
        pendingInputs.restore(persisted);
    }

    /**
     * 通过与主循环相同的前台管线立即执行一次 MANUAL LLM Summary Compact。
     */
    public synchronized String compactNow() {
        long startedAt = System.currentTimeMillis();
        int originalMessagesBefore = (int) history.stream()
                .filter(message -> message.sequence().isPresent())
                .count();
        try {
            CompactionService.PreparedWorkingTurn prepared = turnPreparer.prepareWorking(
                    history,
                    CompactionService.Trigger.MANUAL,
                    0,
                    permissionContextStore.current().workingDir()
            );
            events.contextUsage(prepared, compactConfig);
            if (prepared.strategy() == CompactionService.Strategy.NONE) {
                events.compactionSkipped(CompactionService.Trigger.MANUAL, "NO_COMPACTABLE_HISTORY");
                return "当前没有可压缩内容";
            }
            history = prepared.messages();
            events.compactionCompleted(
                    CompactionService.Trigger.MANUAL,
                    prepared,
                    System.currentTimeMillis() - startedAt
            );
            int recentMessages = (int) history.stream()
                    .filter(message -> message.sequence().isPresent())
                    .count();
            String summaryVersion = prepared.summaryVersion() == null
                    ? "-"
                    : prepared.summaryVersion().toString();
            return """
                    压缩策略: %s
                    压缩前 inputTokens: %d
                    压缩后 inputTokens: %d
                    覆盖消息数: %d
                    保留最近消息数: %d
                    summaryCommit: %s
                    summaryVersion: %s
                    """.formatted(
                    prepared.strategy(),
                    prepared.preCompactInputTokens(),
                    prepared.inputTokens(),
                    Math.max(0, originalMessagesBefore - recentMessages),
                    recentMessages,
                    prepared.summaryCommit() == null ? "SKIPPED" : prepared.summaryCommit(),
                    summaryVersion
            ).trim();
        } catch (CompactionService.PreparationException failure) {
            events.compactionFailed(
                    CompactionService.Trigger.MANUAL,
                    failure.errorCode(),
                    System.currentTimeMillis() - startedAt
            );
            return "压缩失败 [%s]：当前上下文保持不变".formatted(failure.errorCode());
        }
    }

    /**
     * 返回当前完整请求容量、最近边界和活跃会话摘要，不修改上下文。
     */
    public synchronized String compactionStatus() {
        CompactionService.CapacityInfo capacity = turnPreparer.inspect(
                history,
                permissionContextStore.current().workingDir()
        );
        String boundary = history.stream()
                .map(WorkingMessage::message)
                .filter(CompactBoundary::isFullBoundary)
                .reduce((first, second) -> second)
                .map(Object::toString)
                .orElse("none");
        String sessionSummary = summaryState == null
                ? "none"
                : summaryState.current()
                .map(value -> "version=%d, coveredSequence=%d".formatted(
                        value.summaryVersion(),
                        value.coveredSequence()
                ))
                .orElse("none");
        return """
                inputTokens: %d
                capacityState: %s
                workingMessages: %d
                latestBoundary: %s
                sessionSummary: %s
                """.formatted(
                capacity.inputTokens(),
                capacity.capacityState(),
                history.size(),
                boundary,
                sessionSummary
        ).trim();
    }

    /**
     * 只识别供应商明确的上下文长度错误，避免把网络或鉴权失败误判为可压缩问题。
     */
    private static boolean isPromptTooLong(Throwable throwable) {
        String message = ModelCallExecutor.safeErrorMessage(throwable).toLowerCase();
        return message.contains("context_length_exceeded")
                || message.contains("maximum context length")
                || message.contains("too many tokens")
                || message.contains("prompt too long")
                || message.contains("token limit");
    }

    /**
     * 把已完成任务通知按原顺序编码为下一模型轮次可读的数据块。
     */
    private static String formatNotificationBlock(String tag, List<TaskNotification> notifications) {
        String items = notifications.stream()
                .map(notification -> "- %s [%s] %s".formatted(
                        notification.taskId(),
                        notification.status(),
                        notification.content()
                ))
                .collect(Collectors.joining("\n"));
        String content = items.isEmpty() ? "" : items + "\n";
        return "<%s>\n%s</%s>".formatted(tag, content, tag);
    }

    /**
     * 在模型调用前一次性取走主后台任务和子 Agent 的全部已完成通知。
     */
    private List<TaskNotification> drainTaskNotifications() {
        List<TaskNotification> notifications = new ArrayList<>();
        if (bgManager != null) {
            notifications.addAll(bgManager.drain());
        }
        if (subagentService != null) {
            notifications.addAll(subagentService.drainNotifications());
        }
        return notifications;
    }

    /**
     * 只把稳定点快照提交给长期记忆提取；显式写入与后台提取共享同一语义去重规则。
     */
    private void fireLongTermMemoryExtraction(LoopState state, boolean mainAgentWroteMemory) {
        if (memoryExtractionCoordinator != null) {
            memoryExtractionCoordinator.submitStable(state.stableSequence(), state.messages(), mainAgentWroteMemory);
        }
    }
}
