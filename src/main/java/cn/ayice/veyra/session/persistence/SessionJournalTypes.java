package cn.ayice.veyra.session.persistence;

import java.util.Set;

/**
 * 第一版 Session Journal 的稳定事件名称。
 */
public final class SessionJournalTypes {
    public static final String SESSION_CREATED = "session.created";
    public static final String SESSION_SETTINGS_UPDATED = "session.settings.updated";
    public static final String RUN_STARTED = "run.started";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";
    public static final String RUN_CANCELLED = "run.cancelled";
    public static final String RUN_INTERRUPTED = "run.interrupted";
    public static final String USER_MESSAGE_RECORDED = "user.message.recorded";
    public static final String ASSISTANT_MESSAGE_RECORDED = "assistant.message.recorded";
    public static final String CONTEXT_SUMMARY_RECORDED = "context.summary.recorded";
    public static final String TOOL_EXECUTION_STARTED = "tool.execution.started";
    public static final String TOOL_RESULT_RECORDED = "tool.result.recorded";
    public static final String TASK_STARTED = "task.started";
    public static final String TASK_FINISHED = "task.finished";

    public static final Set<String> RUN_TERMINALS = Set.of(
            RUN_COMPLETED, RUN_FAILED, RUN_CANCELLED, RUN_INTERRUPTED
    );

    private SessionJournalTypes() {
    }
}
