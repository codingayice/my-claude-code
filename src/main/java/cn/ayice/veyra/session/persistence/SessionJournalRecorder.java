package cn.ayice.veyra.session.persistence;

import cn.ayice.veyra.compaction.SessionSummaryState;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 一个活动 Session 独占的稳定事实记录器。
 */
public final class SessionJournalRecorder implements JournalMessageRecorder {

    private final String sessionId;
    private final SessionJournalStore store;
    private String currentRunId;
    private String acceptedInput;
    private boolean sessionPersisted;

    public SessionJournalRecorder(String sessionId, SessionJournalStore store) {
        this(sessionId, store, false);
    }

    public SessionJournalRecorder(String sessionId, SessionJournalStore store, boolean sessionPersisted) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.store = Objects.requireNonNull(store, "store");
        this.sessionPersisted = sessionPersisted;
    }

    /** 将后续事实绑定到当前 Run。 */
    public synchronized void bindRun(String runId) {
        currentRunId = runId;
    }

    /** 持久化空 Session 和初始设置。 */
    public synchronized void recordSessionCreated(Path workingDir, String permissionMode, String runMode) {
        if (sessionPersisted) {
            return;
        }
        store.append(sessionId, null, SessionJournalTypes.SESSION_CREATED, Map.of(
                "workingDir", workingDir.toAbsolutePath().normalize().toString(),
                "permissionMode", permissionMode,
                "runMode", runMode
        ), true);
        sessionPersisted = true;
    }

    /** 持久化完整 Session 设置快照。 */
    public synchronized void recordSettings(Path workingDir, String permissionMode, String runMode) {
        recordSettings(workingDir, permissionMode, runMode, null);
    }

    /** 使用乐观 revision 持久化完整 Session 设置。 */
    public synchronized void recordSettings(
            Path workingDir, String permissionMode, String runMode, Long expectedRevision
    ) {
        if (!sessionPersisted) {
            return;
        }
        store.append(sessionId, null, SessionJournalTypes.SESSION_SETTINGS_UPDATED, Map.of(
                "workingDir", workingDir.toAbsolutePath().normalize().toString(),
                "permissionMode", permissionMode,
                "runMode", runMode
        ), true, expectedRevision, java.util.UUID.randomUUID().toString());
    }

    /** 持久化 Run 受理和首条用户消息，并建立唯一活动 Run。 */
    public synchronized void acceptRun(
            String runId,
            String input,
            String mode,
            Path workingDir,
            String permissionMode,
            String runMode
    ) {
        acceptRun(runId, input, mode, null, workingDir, permissionMode, runMode);
    }

    /** 从当前位置或指定历史父 Run 受理一次运行。 */
    public synchronized void acceptRun(
            String runId,
            String input,
            String mode,
            String requestedParentRunId,
            Path workingDir,
            String permissionMode,
            String runMode
    ) {
        if (currentRunId != null) {
            throw new IllegalStateException("SESSION_ALREADY_RUNNING");
        }
        recordSessionCreated(workingDir, permissionMode, runMode);
        String parentRunId = requestedParentRunId == null || requestedParentRunId.isBlank()
                ? store.index(sessionId).currentRunId()
                : requestedParentRunId;
        store.append(sessionId, runId, SessionJournalTypes.RUN_STARTED, Map.of(
                "mode", mode,
                "input", input,
                "parentRunId", parentRunId == null ? "" : parentRunId
        ), false);
        store.append(sessionId, runId, SessionJournalTypes.USER_MESSAGE_RECORDED, Map.of(
                "text", input,
                "visible", true
        ), true);
        currentRunId = runId;
        acceptedInput = input;
    }

    /** 将一条稳定模型消息写入当前 Run。 */
    @Override
    public synchronized void record(ChatMessage message) {
        if (!sessionPersisted || currentRunId == null) {
            return;
        }
        if (message instanceof UserMessage user
                && acceptedInput != null
                && acceptedInput.equals(user.hasSingleText() ? user.singleText() : user.toString())) {
            acceptedInput = null;
            return;
        }
        String type = JournalMessageCodec.typeOf(message);
        boolean durable = !SessionJournalTypes.USER_MESSAGE_RECORDED.equals(type)
                || message instanceof dev.langchain4j.data.message.AiMessage ai && ai.hasToolExecutionRequests();
        store.append(sessionId, currentRunId, type, JournalMessageCodec.encode(message), durable);
    }

    /** 在真实工具调用前持久化执行开始事实。 */
    public synchronized void recordToolStarted(String toolUseId, String name) {
        if (!sessionPersisted || currentRunId == null) {
            return;
        }
        store.append(sessionId, currentRunId, SessionJournalTypes.TOOL_EXECUTION_STARTED, Map.of(
                "toolUseId", toolUseId,
                "name", name
        ), true);
    }

    /** 从明确的领域边界写入一个当前 Run 稳定事件。 */
    public synchronized void recordDomainEvent(String type, Map<String, Object> payload) {
        if (!sessionPersisted || currentRunId == null) {
            return;
        }
        store.append(sessionId, currentRunId, type, payload, true);
    }

    /** 在会话摘要发布到内存前持久化不可变快照。 */
    public synchronized void recordSessionSummary(SessionSummaryState.SummarySnapshot summary) {
        if (!sessionPersisted || currentRunId == null) {
            return;
        }
        store.append(sessionId, currentRunId, SessionJournalTypes.CONTEXT_SUMMARY_RECORDED, Map.of(
                "summaryText", summary.summaryText(),
                "coveredSequence", summary.coveredSequence(),
                "summaryVersion", summary.summaryVersion()
        ), true);
    }

    /** 为当前 Run 写入至多一个终态并释放活动标记。 */
    public synchronized void finishRun(String terminalType, Map<String, Object> payload) {
        if (!sessionPersisted || currentRunId == null) {
            return;
        }
        store.append(sessionId, currentRunId, terminalType, payload, true);
        currentRunId = null;
        acceptedInput = null;
    }

    /** 返回当前尚未终止的 Run 标识。 */
    public synchronized String currentRunId() {
        return currentRunId;
    }

}
