package cn.ayice.veyra.kernel.agent;

import cn.ayice.veyra.conversation.context.ContextBudgetService;
import cn.ayice.veyra.conversation.context.ContextBuilder;
import cn.ayice.veyra.conversation.context.FinalRequestValidator;
import cn.ayice.veyra.conversation.context.WorkingMessage;
import cn.ayice.veyra.conversation.context.compaction.AutoCompactConfig;
import cn.ayice.veyra.conversation.context.compaction.CompactBoundary;
import cn.ayice.veyra.conversation.context.compaction.CompactStrategy;
import cn.ayice.veyra.conversation.context.compaction.CompactTrigger;
import cn.ayice.veyra.conversation.context.compaction.ConversationChunker;
import cn.ayice.veyra.conversation.context.compaction.LlmSummaryCompactor;
import cn.ayice.veyra.conversation.context.compaction.MicroCompactor;
import cn.ayice.veyra.conversation.context.compaction.SessionCheckpointState;
import cn.ayice.veyra.conversation.context.compaction.SessionSummaryCoordinator;
import cn.ayice.veyra.conversation.transcript.TranscriptRecorder;
import cn.ayice.veyra.kernel.event.AgentEventSink;
import cn.ayice.veyra.kernel.memory.MemoryExtractionCoordinator;
import cn.ayice.veyra.kernel.model.ModelCallExecutor;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tooling.ToolDispatcher;
import cn.ayice.veyra.tooling.ToolEngine;
import cn.ayice.veyra.tooling.ToolExecutionConfirmation;
import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionContextStore;
import cn.ayice.veyra.tooling.state.FileStateCache;
import cn.ayice.veyra.tooling.state.TodoManager;
import cn.ayice.veyra.tooling.task.AgentTaskManager;
import cn.ayice.veyra.tooling.task.BackgroundManager;
import cn.ayice.veyra.tooling.task.TaskNotification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 主 Agent 对话循环。它在 process 之间持有唯一 Working History，并按轮执行模型与完整工具批次。
 */
public class AgentLoop {

    private static final int TODO_REMINDER_GRACE_ROUNDS = 3;
    private static final int MODEL_FAILURE_LIMIT = 3;
    private static final Logger logger = LoggerFactory.getLogger(AgentLoop.class);

    private final AgentTurnPreparer turnPreparer;
    private final AIService ai;
    private final AgentToolCoordinator toolCoordinator;
    private final PermissionContextStore permissionContextStore;
    private final TodoManager todoManager;
    private final BackgroundManager bgManager;
    private final AgentTaskManager agentTaskManager;
    private final AgentLoopEvents events;
    private final AutoCompactConfig compactConfig;
    private final int maxRounds;
    private final SessionSummaryCoordinator sessionSummaryCoordinator;
    private final SessionCheckpointState checkpointState;
    private final MemoryExtractionCoordinator memoryExtractionCoordinator;
    private final long modelCallTimeoutMs;
    private final TranscriptRecorder transcriptRecorder;

    private PermissionContext permissionContext;
    private List<WorkingMessage> history;
    private long nextSequence;
    private long completedModelRounds;
    private boolean todoWriteUsed;

    public AgentLoop(
            AIService ai,
            ToolDispatcher tools,
            ContextBuilder contextBuilder,
            BackgroundManager bgManager,
            ToolExecutionConfirmation confirmation,
            PermissionContextStore permissionContextStore,
            TodoManager todoManager,
            AutoCompactConfig compactConfig,
            int maxRounds,
            AgentTaskManager agentTaskManager,
            AgentEventSink eventSink,
            SessionCheckpointState checkpointState,
            SessionSummaryCoordinator sessionSummaryCoordinator,
            FileStateCache fileStateCache,
            long modelCallTimeoutMs,
            MemoryExtractionCoordinator memoryExtractionCoordinator,
            List<ChatMessage> initialHistory,
            TranscriptRecorder transcriptRecorder,
            Executor toolExecutor
    ) {
        if (modelCallTimeoutMs <= 0) {
            throw new IllegalArgumentException("modelCallTimeoutMs must be positive");
        }
        this.compactConfig = compactConfig;
        this.checkpointState = checkpointState;
        this.sessionSummaryCoordinator = sessionSummaryCoordinator;
        this.turnPreparer = new AgentTurnPreparer(
                contextBuilder,
                compactConfig,
                new ContextBudgetService(compactConfig),
                new MicroCompactor(),
                checkpointState,
                new LlmSummaryCompactor(ai, new ConversationChunker()),
                new FinalRequestValidator(),
                fileStateCache
        );
        this.ai = ai;
        ToolEngine toolEngine = new ToolEngine(tools, confirmation, permissionContextStore);
        this.permissionContextStore = permissionContextStore;
        this.permissionContext = permissionContextStore.current();
        this.todoManager = todoManager;
        this.bgManager = bgManager;
        this.agentTaskManager = agentTaskManager;
        this.events = new AgentLoopEvents(eventSink);
        this.maxRounds = maxRounds;
        this.memoryExtractionCoordinator = memoryExtractionCoordinator;
        this.modelCallTimeoutMs = modelCallTimeoutMs;
        this.transcriptRecorder = transcriptRecorder;
        this.history = new ArrayList<>(initialHistory.size());
        for (ChatMessage message : initialHistory) {
            this.history.add(WorkingMessage.original(++nextSequence, message));
        }
        this.toolCoordinator = new AgentToolCoordinator(
                toolEngine,
                permissionContextStore,
                this.events,
                transcriptRecorder,
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
        if (checkpointState != null) {
            checkpointState.close();
        }
        if (agentTaskManager != null) {
            agentTaskManager.shutdown();
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
        transcriptRecorder.record(UserMessage.from(input));
        boolean promptTooLongCompactionAttempted = false;
        boolean mainAgentWroteMemory = false;

        while (true) {
            if (state.isTerminal()) {
                history = state.messages();
                nextSequence = state.nextSequence();
                String terminal = terminalMessage(state);
                events.runCompleted(state.state(), terminal);
                return terminal;
            }

            List<TaskNotification> notifications = drainTaskNotifications();
            if (!notifications.isEmpty()) {
                state = state.appendOriginal(UserMessage.from(
                        formatNotificationBlock("task_notifications", notifications)
                )).markStable();
            }

            AgentTurnPreparer.PreparedWorkingTurn prepared;
            long prepareStartedAt = System.currentTimeMillis();
            try {
                prepared = turnPreparer.prepareWorking(
                        state.messages(),
                        CompactTrigger.AUTO,
                        completedModelRounds,
                        permissionContext.workingDir()
                );
            } catch (AgentTurnPreparer.PreparationException preparationFailure) {
                events.compactionFailed(
                        CompactTrigger.AUTO,
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
            if (prepared.strategy() != CompactStrategy.NONE) {
                events.compactionCompleted(
                        CompactTrigger.AUTO,
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
            try {
                CompletableFuture<AiMessage> future = ai.streamingChat(
                        request.messages(),
                        request.toolSpecifications(),
                        events::assistantToken
                );
                aiMessage = ModelCallExecutor.await(future, modelCallTimeoutMs);
            } catch (Exception modelFailure) {
                if (!promptTooLongCompactionAttempted && isPromptTooLong(modelFailure)) {
                    promptTooLongCompactionAttempted = true;
                    long reactiveStartedAt = System.currentTimeMillis();
                    try {
                        AgentTurnPreparer.PreparedWorkingTurn reactive = turnPreparer.prepareWorking(
                                state.messages(),
                                CompactTrigger.REACTIVE,
                                completedModelRounds,
                                permissionContext.workingDir()
                        );
                        state = state.withMessages(reactive.messages())
                                .withCapacityState(reactive.capacityState())
                                .withRequest(null)
                                .withState(LoopState.ACTIVE, "prompt_too_long_compacted")
                                .markStable();
                        events.contextUsage(reactive, compactConfig);
                        if (reactive.strategy() != CompactStrategy.NONE) {
                            events.compactionCompleted(
                                    CompactTrigger.REACTIVE,
                                    reactive,
                                    System.currentTimeMillis() - reactiveStartedAt
                            );
                        }
                        continue;
                    } catch (AgentTurnPreparer.PreparationException reactiveFailure) {
                        events.compactionFailed(
                                CompactTrigger.REACTIVE,
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
                            .withTerminalState(LoopState.TERMINAL_FAILED, "model_call_failed");
                    continue;
                }
                state = state.withFailureCount(nextFailureCount)
                        .withAiMessage(null)
                        .withApprovedRequests(null)
                        .withRequest(null)
                        .withState(LoopState.ACTIVE, "model_call_failed");
                continue;
            }

            state = state.appendOriginal(aiMessage);
            completedModelRounds++;
            transcriptRecorder.record(aiMessage);
            events.assistantCompleted(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                state = state.markStable();
                submitStableSnapshot(state);
                fireLongTermMemoryExtraction(state.chatMessages(), mainAgentWroteMemory);
                state = state.withAiMessage(aiMessage)
                        .withRequest(request)
                        .withFailureCount(0)
                        .withTurnCount(state.turnCount() + 1)
                        .withApprovedRequests(null)
                        .withTerminalState(LoopState.TERMINAL_COMPLETED, "completed");
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
                state = state.appendOriginal(UserMessage.from(
                        "<system-reminder>Todo 列表仍有未完成项。请继续处理或关闭这些事项后再进入下一步。</system-reminder>"
                )).markStable();
            }
            if (!todoWriteUsed && nextRound >= TODO_REMINDER_GRACE_ROUNDS
                    && nextRound % TODO_REMINDER_GRACE_ROUNDS == 0
                    && todoManager != null && !todoManager.hasOpenItems()) {
                state = state.appendOriginal(UserMessage.from(
                        "<system-reminder>你还没有使用 TodoWrite 工具规划任务。如果当前任务涉及 3 个以上独立步骤，请先用 TodoWrite 创建任务清单，再逐步执行。</system-reminder>"
                )).markStable();
            }
            if (maxRounds > 0 && nextRound > maxRounds) {
                state = state.withAiMessage(aiMessage)
                        .withRequest(request)
                        .withApprovedRequests(null)
                        .withTerminalState(LoopState.TERMINAL_MAX_ROUNDS, "max_turns");
                continue;
            }
            state = state.withAiMessage(aiMessage)
                    .withRequest(request)
                    .withApprovedRequests(null)
                    .withFailureCount(0)
                    .withTurnCount(nextRound)
                    .withState(LoopState.ACTIVE, "next_turn");
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
        return switch (state.state()) {
            case LoopState.TERMINAL_COMPLETED -> state.aiMessage() != null ? state.aiMessage().text() : "";
            case LoopState.TERMINAL_MAX_ROUNDS ->
                    "<error>已达到最大轮数 (" + maxRounds + ")，任务仍未完成。对话结束。</error>";
            case LoopState.TERMINAL_FAILED -> "<error>LLM 调用连续失败次数过多。对话结束。</error>";
            case LoopState.TERMINAL_CANCELLED -> "<error>对话已取消。</error>";
            default -> state.aiMessage() != null ? state.aiMessage().text() : "";
        };
    }

    /**
     * 返回去除 sequence 包装后的当前 Agent 历史副本。
     */
    public List<ChatMessage> getHistory() {
        return WorkingMessage.unwrap(history);
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
            AgentTurnPreparer.PreparedWorkingTurn prepared = turnPreparer.prepareWorking(
                    history,
                    CompactTrigger.MANUAL,
                    0,
                    permissionContextStore.current().workingDir()
            );
            events.contextUsage(prepared, compactConfig);
            if (prepared.strategy() == CompactStrategy.NONE) {
                events.compactionSkipped(CompactTrigger.MANUAL, "NO_COMPACTABLE_HISTORY");
                return "当前没有可压缩内容";
            }
            history = prepared.messages();
            events.compactionCompleted(
                    CompactTrigger.MANUAL,
                    prepared,
                    System.currentTimeMillis() - startedAt
            );
            int recentMessages = (int) history.stream()
                    .filter(message -> message.sequence().isPresent())
                    .count();
            String checkpointVersion = prepared.checkpointVersion() == null
                    ? "-"
                    : prepared.checkpointVersion().toString();
            return """
                    压缩策略: %s
                    压缩前 inputTokens: %d
                    压缩后 inputTokens: %d
                    覆盖消息数: %d
                    保留最近消息数: %d
                    checkpointCommit: %s
                    checkpointVersion: %s
                    """.formatted(
                    prepared.strategy(),
                    prepared.preCompactInputTokens(),
                    prepared.inputTokens(),
                    Math.max(0, originalMessagesBefore - recentMessages),
                    recentMessages,
                    prepared.checkpointCommit() == null ? "SKIPPED" : prepared.checkpointCommit(),
                    checkpointVersion
            ).trim();
        } catch (AgentTurnPreparer.PreparationException failure) {
            events.compactionFailed(
                    CompactTrigger.MANUAL,
                    failure.errorCode(),
                    System.currentTimeMillis() - startedAt
            );
            return "压缩失败 [%s]：当前上下文保持不变".formatted(failure.errorCode());
        }
    }

    /**
     * 返回当前完整请求容量、最近边界和活跃 checkpoint，不修改上下文。
     */
    public synchronized String compactionStatus() {
        AgentTurnPreparer.CapacityInfo capacity = turnPreparer.inspect(
                history,
                permissionContextStore.current().workingDir()
        );
        String boundary = history.stream()
                .map(WorkingMessage::message)
                .filter(CompactBoundary::isFullBoundary)
                .reduce((first, second) -> second)
                .map(Object::toString)
                .orElse("none");
        String checkpoint = checkpointState == null
                ? "none"
                : checkpointState.current()
                .map(value -> "version=%d, coveredSequence=%d".formatted(
                        value.checkpointVersion(),
                        value.coveredSequence()
                ))
                .orElse("none");
        return """
                inputTokens: %d
                capacityState: %s
                workingMessages: %d
                latestBoundary: %s
                checkpoint: %s
                """.formatted(
                capacity.inputTokens(),
                capacity.capacityState(),
                history.size(),
                boundary,
                checkpoint
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
        if (agentTaskManager != null) {
            notifications.addAll(agentTaskManager.drain());
        }
        return notifications;
    }

    /**
     * 最终回复后提交长期记忆提取；本轮显式写过记忆时由协调器跳过重复提取。
     */
    private void fireLongTermMemoryExtraction(List<ChatMessage> messages, boolean skip) {
        if (memoryExtractionCoordinator != null) {
            memoryExtractionCoordinator.submit(messages, skip);
        }
    }
}
