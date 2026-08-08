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

    public SessionJournalRecorder(String sessionId, SessionJournalStore store) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.store = Objects.requireNonNull(store, "store");
    }

    /** 将后续事实绑定到当前 Run。 */
    public synchronized void bindRun(String runId) {
        currentRunId = runId;
    }

    /** 持久化空 Session 和初始设置。 */
    public synchronized void recordSessionCreated(Path workingDir, String permissionMode, String runMode) {
        store.append(sessionId, null, SessionJournalTypes.SESSION_CREATED, Map.of(
                "workingDir", workingDir.toAbsolutePath().normalize().toString(),
                "permissionMode", permissionMode,
                "runMode", runMode
        ), true);
    }

    /** 持久化完整 Session 设置快照。 */
    public synchronized void recordSettings(Path workingDir, String permissionMode, String runMode) {
        store.append(sessionId, null, SessionJournalTypes.SESSION_SETTINGS_UPDATED, Map.of(
                "workingDir", workingDir.toAbsolutePath().normalize().toString(),
                "permissionMode", permissionMode,
                "runMode", runMode
        ), true);
    }

    /** 持久化 Run 受理和首条用户消息，并建立唯一活动 Run。 */
    public synchronized void acceptRun(String runId, String input, String mode) {
        if (currentRunId != null) {
            throw new IllegalStateException("SESSION_ALREADY_RUNNING");
        }
        store.append(sessionId, runId, SessionJournalTypes.RUN_STARTED, Map.of(
                "mode", mode,
                "input", input
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
        store.append(sessionId, currentRunId, SessionJournalTypes.TOOL_EXECUTION_STARTED, Map.of(
                "toolUseId", toolUseId,
                "name", name
        ), true);
    }

    /**
     * 持久化影响冷加载 UI 的稳定运行事件。消息、工具结果和 Run 生命周期由各自的
     * 专用写入点负责，避免同一事实重复进入 Journal。
     */
    public synchronized void recordStableEvent(String type, Map<String, Object> payload) {
        if (!isStableUiEvent(type)) {
            return;
        }
        store.append(sessionId, currentRunId, type, payload, true);
    }

    /** 在会话摘要发布到内存前持久化不可变快照。 */
    public synchronized void recordSessionSummary(SessionSummaryState.SummarySnapshot summary) {
        store.append(sessionId, currentRunId, SessionJournalTypes.CONTEXT_SUMMARY_RECORDED, Map.of(
                "summaryText", summary.summaryText(),
                "coveredSequence", summary.coveredSequence(),
                "summaryVersion", summary.summaryVersion()
        ), true);
    }

    /** 为当前 Run 写入至多一个终态并释放活动标记。 */
    public synchronized void finishRun(String terminalType, Map<String, Object> payload) {
        if (currentRunId == null) {
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

    /** 判断实时事件是否属于必须持久化的稳定 UI 事实。 */
    private static boolean isStableUiEvent(String type) {
        return switch (type) {
            case SessionJournalTypes.TOOL_CALL_STARTED,
                 SessionJournalTypes.PERMISSION_REQUESTED,
                 SessionJournalTypes.PERMISSION_RESOLVED,
                 SessionJournalTypes.PERMISSION_INTERRUPTED,
                 SessionJournalTypes.TODO_UPDATED,
                 SessionJournalTypes.TASK_STARTED,
                 SessionJournalTypes.TASK_STEP_STARTED,
                 SessionJournalTypes.TASK_ASSISTANT_MESSAGE_COMPLETED,
                 SessionJournalTypes.TASK_TOOL_CALL_STARTED,
                 SessionJournalTypes.TASK_TOOL_CALL_COMPLETED,
                 SessionJournalTypes.TASK_TOOL_CALL_REJECTED,
                 SessionJournalTypes.TASK_PERMISSION_REQUESTED,
                 SessionJournalTypes.TASK_PERMISSION_RESOLVED,
                 SessionJournalTypes.TASK_COMPLETED,
                 SessionJournalTypes.TASK_FAILED,
                 SessionJournalTypes.TASK_KILLED -> true;
            default -> false;
        };
    }
}
