package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.session.PendingApprovalState;
import cn.ayice.veyra.session.SessionState;
import cn.ayice.veyra.session.SessionSettings;
import cn.ayice.veyra.session.event.SessionEventStream;
import cn.ayice.veyra.interaction.command.SlashCommandDispatcher;
import cn.ayice.veyra.interaction.command.SlashCommandOption;
import cn.ayice.veyra.interaction.command.SlashCommandResult;
import cn.ayice.veyra.runtime.RunTarget;
import cn.ayice.veyra.runtime.PendingInputQueue;
import cn.ayice.veyra.runtime.RunMode;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.runtime.agent.AgentLoop;
import cn.ayice.veyra.runtime.chat.ChatLoop;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 单个会话全部可变运行状态的唯一所有者。
 * <p>它持有 Agent/Chat 循环、权限、审批、命令和事件流，并通过私有队列保证同会话 Run 串行。</p>
 */
public class SessionRuntime implements RunTarget, AutoCloseable {

    private final String sessionId;
    private final SessionEventStream events;
    private final ToolApprovalQueue confirmation;
    private final AgentLoop agentLoop;
    private final ChatLoop chatLoop;
    private final PermissionContextStore permissionContextStore;
    private final SlashCommandDispatcher slashCommands;
    private final SessionRunQueue runQueue;
    private final SessionJournalRecorder journalRecorder;
    private final cn.ayice.veyra.session.persistence.SessionJournalStore journalStore;
    private final PendingInputQueue pendingInputs;
    private volatile String runMode;
    private volatile String lastRunStatus;

    /**
     * 使用已装配的会话级组件和共享 Run 执行器创建运行时。
     */
    public SessionRuntime(
            String sessionId,
            SessionEventStream events,
            ToolApprovalQueue confirmation,
            AgentLoop agentLoop,
            ChatLoop chatLoop,
            PermissionContextStore permissionContextStore,
            SlashCommandDispatcher slashCommands,
            Executor executor
    ) {
        this(sessionId, events, confirmation, agentLoop, chatLoop, permissionContextStore,
                slashCommands, executor, null, null, "chat", "idle");
    }

    /**
     * 使用可选 Durable Journal 和恢复后的 Run 状态创建 Session Runtime。
     */
    public SessionRuntime(
            String sessionId,
            SessionEventStream events,
            ToolApprovalQueue confirmation,
            AgentLoop agentLoop,
            ChatLoop chatLoop,
            PermissionContextStore permissionContextStore,
            SlashCommandDispatcher slashCommands,
            Executor executor,
            SessionJournalRecorder journalRecorder,
            cn.ayice.veyra.session.persistence.SessionJournalStore journalStore,
            String runMode,
            String lastRunStatus
    ) {
        this.sessionId = sessionId;
        this.events = events;
        this.confirmation = confirmation;
        this.agentLoop = agentLoop;
        this.chatLoop = chatLoop;
        this.permissionContextStore = permissionContextStore;
        this.slashCommands = slashCommands;
        this.runQueue = new SessionRunQueue(executor);
        this.journalRecorder = journalRecorder;
        this.journalStore = journalStore;
        this.pendingInputs = agentLoop.pendingInputs();
        this.runMode = runMode == null || runMode.isBlank() ? "chat" : runMode;
        this.lastRunStatus = lastRunStatus == null ? "idle" : lastRunStatus;
    }

    /**
     * 返回当前会话的稳定标识。
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 返回当前会话独占的事件流。
     */
    public SessionEventStream events() {
        return events;
    }

    /**
     * 将 Run 加入当前会话的串行执行队列。
     */
    public CompletableFuture<Void> enqueue(Runnable run) {
        return runQueue.submit(run);
    }

    /** 将运行期间的新输入加入默认追随队列。 */
    public PendingInputQueue.Message addFollowup(String text, RunMode mode) {
        return pendingInputs.addFollowup(text, mode);
    }

    /** 尝试把尚未消费的追随输入切换为引导。 */
    public boolean steerPendingInput(String messageId) {
        return pendingInputs.steer(messageId);
    }

    /** 取消尚未被 AgentLoop 或后续 Run 消费的输入。 */
    public boolean cancelPendingInput(String messageId) {
        return pendingInputs.cancel(messageId);
    }

    /** 在当前 Run 之后领取指定待处理输入。 */
    public PendingInputQueue.Message takePendingInputForNextRun(String messageId) {
        return pendingInputs.takeForNextRun(messageId);
    }

    /**
     * 从当前权限上下文生成可返回给控制面的会话设置快照。
     */
    public SessionState state() {
        PermissionContext context = permissionContextStore.current();
        Path workingDir = context == null ? null : context.workingDir();
        PermissionMode mode = context == null || context.mode() == null
                ? PermissionMode.ASK_EVERY_TIME
                : context.mode();
        cn.ayice.veyra.session.persistence.SessionIndex index = journalStore == null
                ? cn.ayice.veyra.session.persistence.SessionIndex.empty(sessionId)
                : journalStore.index(sessionId);
        return new SessionState(
                sessionId,
                workingDir == null ? "" : workingDir.toString(),
                mode.configValue(),
                runMode,
                lastRunStatus,
                index.appliedRevision(),
                index.currentRunId(),
                index.activeRunId(),
                index.runs().entrySet().stream().collect(java.util.stream.Collectors.toMap(
                        java.util.Map.Entry::getKey,
                        entry -> new cn.ayice.veyra.session.RunNodeState(
                                entry.getValue().runId(), entry.getValue().parentRunId(),
                                entry.getValue().startedRevision(), entry.getValue().terminalRevision(),
                                entry.getValue().status(), entry.getValue().snapshotAvailable()
                        ),
                        (left, right) -> left,
                        java.util.LinkedHashMap::new
                )),
                journalStore == null
                        ? cn.ayice.veyra.session.state.AgentState.empty()
                        : journalStore.recoveryAgentState(sessionId)
        );
    }

    /** 当前会话是否仍有已受理且尚未终止的 Run。 */
    public boolean isRunning() {
        return "running".equals(lastRunStatus);
    }

    /**
     * 原子更新当前会话的工作目录和权限模式并返回新快照。
     */
    public synchronized SessionState updateSettings(String workingDir, String permissionMode, String requestedRunMode) {
        return updateSettings(workingDir, permissionMode, requestedRunMode, null);
    }

    /** 使用 expectedRevision 更新 Session 设置。 */
    public synchronized SessionState updateSettings(
            String workingDir, String permissionMode, String requestedRunMode, Long expectedRevision
    ) {
        Path nextWorkingDir = workingDir == null || workingDir.isBlank()
                ? null
                : Path.of(workingDir);
        PermissionContext current = permissionContextStore.current();
        PermissionContext next = current == null ? PermissionContext.builder().build() : current;
        PermissionMode mode = permissionMode == null || permissionMode.isBlank()
                ? next.mode()
                : PermissionMode.fromString(permissionMode);
        if (nextWorkingDir != null) {
            next = next.withWorkingDirectory(nextWorkingDir);
        }
        next = next.withMode(mode);
        Path persistedWorkingDir = next.workingDir();
        if (persistedWorkingDir == null) {
            throw new IllegalArgumentException("workingDir must be configured before persisting settings");
        }
        SessionSettings normalized = new SessionSettings(
                persistedWorkingDir,
                mode.configValue(),
                requestedRunMode == null || requestedRunMode.isBlank() ? runMode : requestedRunMode
        );
        if (journalRecorder != null) {
            journalRecorder.recordSettings(
                    normalized.workingDir(),
                    normalized.permissionMode(),
                    normalized.runMode(),
                    expectedRevision
            );
        }
        PermissionContext committed = next;
        permissionContextStore.update(ignored -> committed);
        runMode = normalized.runMode();
        return state();
    }

    /**
     * 原子持久化 Run 受理事实；已有未终止 Run 时拒绝。
     */
    public synchronized boolean acceptRun(String runId, String input, String mode) {
        return acceptRun(runId, input, mode, null);
    }

    /** 从当前位置或指定历史终态 Run 受理一次新 Run。 */
    public synchronized boolean acceptRun(String runId, String input, String mode, String parentRunId) {
        if (journalRecorder == null) {
            return true;
        }
        try {
            PermissionContext context = permissionContextStore.current();
            journalRecorder.acceptRun(
                    runId,
                    input,
                    mode,
                    parentRunId,
                    context.workingDir(),
                    context.mode().configValue(),
                    runMode
            );
            lastRunStatus = "running";
            return true;
        } catch (IllegalStateException alreadyRunning) {
            if ("SESSION_ALREADY_RUNNING".equals(alreadyRunning.getMessage())) {
                return false;
            }
            throw alreadyRunning;
        }
    }

    /**
     * Run 入队失败时写入稳定失败终态。
     */
    public synchronized void failEnqueue() {
        if (journalRecorder != null) {
            journalRecorder.finishRun(SessionJournalTypes.RUN_FAILED, Map.of("reason", "enqueue_failed"));
        }
        lastRunStatus = "failed";
    }

    /**
     * 返回等待当前用户处理的工具审批快照。
     */
    public List<PendingApprovalState> pendingApprovals() {
        return confirmation.pendingApprovals().stream()
                .map(item -> new PendingApprovalState(
                        item.approvalId(),
                        item.tool(),
                        item.arguments(),
                        item.reason()
                ))
                .toList();
    }

    /**
     * 解析并提交一项工具审批决定。
     */
    public boolean resolveApproval(String approvalId, String decision) {
        return confirmation.resolveApproval(approvalId, decision);
    }

    /**
     * 返回与查询文本匹配的斜杠命令选项。
     */
    public List<SlashCommandOption> commandOptions(String query) {
        return slashCommands.suggest(query);
    }

    /**
     * 分发斜杠命令并返回执行结果。
     */
    public Optional<SlashCommandResult> findCommand(String command) {
        return slashCommands.dispatch(command);
    }

    /**
     * 返回主 Agent 当前历史的防御性副本。
     */
    public List<dev.langchain4j.data.message.ChatMessage> agentHistory() {
        return agentLoop.getHistory();
    }

    /**
     * 返回 Chat 模式当前历史的防御性副本。
     */
    public List<dev.langchain4j.data.message.ChatMessage> chatHistory() {
        return chatLoop.getHistory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bindRun(String runId) {
        events.bindRun(runId);
        if (journalRecorder != null) {
            journalRecorder.bindRun(runId);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void emit(String type, Map<String, Object> payload) {
        events.emit(type, payload);
    }

    /** 同时写入领域事实并发布实时通知。 */
    public void emitStable(String type, Map<String, Object> payload) {
        if (journalRecorder != null) {
            journalRecorder.recordDomainEvent(type, payload);
        }
        events.emit(type, payload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void executeAgent(String input) {
        agentLoop.process(input);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void executeChat(String input) {
        chatLoop.process(input);
    }

    /** 持久化当前 Run 的正常终态。 */
    @Override
    public void completeRun(Map<String, Object> payload) {
        if (journalRecorder != null) {
            journalRecorder.finishRun(SessionJournalTypes.RUN_COMPLETED, payload);
        }
        lastRunStatus = "completed";
    }

    /** 持久化当前 Run 的失败终态。 */
    @Override
    public void failRun(Map<String, Object> payload) {
        if (journalRecorder != null) {
            journalRecorder.finishRun(SessionJournalTypes.RUN_FAILED, payload);
        }
        lastRunStatus = "failed";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        agentLoop.shutdown();
    }
}
