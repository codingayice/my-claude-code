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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import cn.ayice.veyra.runtime.control.RunControlRequest;
import cn.ayice.veyra.runtime.control.RunControlResult;

/**
 * 单个会话全部可变运行状态的唯一所有者。
 * <p>它持有 Agent/Chat 循环、权限、审批、命令和事件流，并通过私有队列保证同会话 Run 串行。</p>
 */
public class SessionRuntime implements RunTarget, AutoCloseable {

    private final String sessionId;
    private final SessionEventStream events;
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
            AgentLoop agentLoop,
            ChatLoop chatLoop,
            PermissionContextStore permissionContextStore,
            SlashCommandDispatcher slashCommands,
            Executor executor
    ) {
        this(sessionId, events, agentLoop, chatLoop, permissionContextStore,
                slashCommands, executor, null, null, "chat", "idle");
    }

    /**
     * 使用可选 Durable Journal 和恢复后的 Run 状态创建 Session Runtime。
     */
    public SessionRuntime(
            String sessionId,
            SessionEventStream events,
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

    /** 按 runId 去重提交一次状态机推进。 */
    public CompletableFuture<Void> enqueueRun(String runId, Runnable run) {
        return runQueue.submit(runId, run);
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
        if (journalStore == null) return List.of();
        return journalStore.recoveryAgentState(sessionId).approvals().values().stream()
                .filter(item -> item.status() == PendingApprovalState.ApprovalStatus.PENDING)
                .toList();
    }


    /** 在 Session 单写者边界内校验并持久化统一控制请求。 */
    public synchronized RunControlResult control(String runId, RunControlRequest request) {
        validateControlRequest(request);
        String requestKey = controlRequestKey(runId, request);
        Map<String, String> decisions = "resume".equals(request.action())
                ? approvalDecisions(request.input()) : Map.of();
        List<cn.ayice.veyra.session.persistence.SessionJournalEntry> duplicates = journalStore.read(sessionId).stream()
                .filter(event -> request.commandId().equals(event.payload().get("commandId")))
                .toList();
        if (!duplicates.isEmpty()) {
            if (duplicates.stream().anyMatch(event -> !requestKey.equals(event.payload().get("requestKey")))) {
                throw new IllegalStateException("COMMAND_ID_REUSED");
            }
            if ("cancel".equals(request.action()) || duplicates.size() == decisions.size()) {
                long revision = duplicates.stream().mapToLong(
                        cn.ayice.veyra.session.persistence.SessionJournalEntry::sequence).max().orElseThrow();
                return new RunControlResult("accepted", runId, revision, Map.of("idempotent", true));
            }
        }
        cn.ayice.veyra.session.persistence.SessionIndex index = journalStore.index(sessionId);
        if (duplicates.isEmpty() && request.expectedRevision() != index.appliedRevision()) {
            throw new IllegalStateException("SESSION_REVISION_CONFLICT");
        }
        if (!index.runs().containsKey(runId)) throw new IllegalStateException("RUN_NOT_FOUND");
        if (!runId.equals(index.activeRunId())) throw new IllegalStateException("RUN_NOT_ACTIVE");
        if ("cancel".equals(request.action())) {
            cn.ayice.veyra.session.persistence.SessionJournalEntry event = journalStore.append(
                    sessionId, runId, SessionJournalTypes.RUN_CANCELLED,
                    Map.of("reason", request.cause(), "commandId", request.commandId(), "requestKey", requestKey), true,
                    request.expectedRevision(), request.commandId());
            lastRunStatus = "cancelled";
            if (journalRecorder != null) journalRecorder.releaseRun(runId);
            events.emit("run.cancelled", Map.of("reason", request.cause(), "commandId", request.commandId()));
            return new RunControlResult("accepted", runId, event.sequence(), Map.of());
        }
        cn.ayice.veyra.session.state.AgentState state = journalStore.recoveryAgentState(sessionId);
        if (state.run() == null || state.run().phase() != cn.ayice.veyra.session.state.AgentPhase.WAITING_APPROVAL) {
            throw new IllegalStateException("RUN_NOT_SUSPENDED");
        }
        for (String approvalId : decisions.keySet()) {
            PendingApprovalState approval = state.approvals().get(approvalId);
            if (approval == null) throw new IllegalStateException("APPROVAL_NOT_FOUND");
            if (approval.status() == PendingApprovalState.ApprovalStatus.RESOLVED) {
                boolean resolvedByCommand = duplicates.stream().anyMatch(event ->
                        approvalId.equals(event.payload().get("approvalId")));
                if (!resolvedByCommand) throw new IllegalStateException("APPROVAL_ALREADY_RESOLVED");
            }
        }
        long revision = index.appliedRevision();
        for (Map.Entry<String, String> decision : decisions.entrySet()) {
            boolean alreadyWritten = duplicates.stream().anyMatch(event ->
                    decision.getKey().equals(event.payload().get("approvalId")));
            if (alreadyWritten) continue;
            Map<String, Object> payload = Map.of(
                    "approvalId", decision.getKey(), "decision", decision.getValue(),
                    "commandId", request.commandId(), "requestKey", requestKey);
            String eventId = decisions.size() == 1 ? request.commandId() : request.commandId() + ":" + decision.getKey();
            cn.ayice.veyra.session.persistence.SessionJournalEntry event = journalStore.append(
                    sessionId, runId, SessionJournalTypes.PERMISSION_RESOLVED, payload, true,
                    revision, eventId);
            revision = event.sequence();
            events.emit("permission.resolved", payload);
        }
        return new RunControlResult("accepted", runId, revision,
                Map.of("approvalIds", List.copyOf(decisions.keySet())));
    }

    /** 校验 action、cause、revision 和命令幂等键。 */
    private static void validateControlRequest(RunControlRequest request) {
        if (request == null || request.action() == null || request.cause() == null
                || request.expectedRevision() == null || request.commandId() == null || request.commandId().isBlank()) {
            throw new IllegalArgumentException("INVALID_RUN_CONTROL");
        }
        boolean supported = ("resume".equals(request.action()) && "approval".equals(request.cause()))
                || ("cancel".equals(request.action()) && "user_requested".equals(request.cause()));
        if (!supported) throw new IllegalArgumentException("UNSUPPORTED_RUN_CONTROL");
    }

    /** 解码互斥的单项或批量审批输入并校验决定值。 */
    @SuppressWarnings("unchecked")
    private static Map<String, String> approvalDecisions(Map<String, Object> input) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        Object batch = input.get("decisions");
        boolean single = input.containsKey("approvalId") || input.containsKey("decision");
        if (batch != null && single) throw new IllegalArgumentException("INVALID_RUN_CONTROL");
        if (batch instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException("INVALID_RUN_CONTROL");
                result.put(String.valueOf(map.get("approvalId")), String.valueOf(map.get("decision")));
            }
        } else if (single) {
            result.put(String.valueOf(input.get("approvalId")), String.valueOf(input.get("decision")));
        }
        if (result.isEmpty() || result.entrySet().stream().anyMatch(entry -> entry.getKey().isBlank()
                || !Set.of("allow_once", "allow_for_session", "deny").contains(entry.getValue()))) {
            throw new IllegalArgumentException("INVALID_RUN_CONTROL");
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    /** 为命令幂等比较生成与 Map 遍历顺序无关的规范文本。 */
    private static String controlRequestKey(String runId, RunControlRequest request) {
        return runId + "|" + request.action() + "|" + request.cause() + "|" + canonical(request.input());
    }

    /** 递归规范化 JSON 兼容值。 */
    private static String canonical(Object value) {
        if (value instanceof Map<?, ?> map) return map.entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry -> String.valueOf(entry.getKey())))
                .map(entry -> entry.getKey() + ":" + canonical(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(",", "{", "}"));
        if (value instanceof List<?> list) return list.stream().map(SessionRuntime::canonical)
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        return String.valueOf(value);
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
    public cn.ayice.veyra.runtime.agent.AgentStepResult executeAgent(String input) {
        return agentLoop.processStep(input);
    }

    /** {@inheritDoc} */
    @Override
    public cn.ayice.veyra.runtime.agent.AgentStepResult resumeAgent() {
        return agentLoop.resume(journalStore.recoveryAgentState(sessionId));
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
        events.emit("session.revision", Map.of());
    }

    /** 持久化当前 Run 的失败终态。 */
    @Override
    public void failRun(Map<String, Object> payload) {
        if (journalRecorder != null) {
            journalRecorder.finishRun(SessionJournalTypes.RUN_FAILED, payload);
        }
        lastRunStatus = "failed";
        events.emit("session.revision", Map.of());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        agentLoop.shutdown();
    }
}
