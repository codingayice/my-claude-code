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
    public static final String TOOL_CALL_STARTED = "tool.call.started";
    public static final String TOOL_EXECUTION_STARTED = "tool.execution.started";
    public static final String TOOL_RESULT_RECORDED = "tool.result.recorded";
    public static final String PERMISSION_REQUESTED = "permission.requested";
    public static final String PERMISSION_RESOLVED = "permission.resolved";
    public static final String PERMISSION_INTERRUPTED = "permission.interrupted";
    public static final String TODO_UPDATED = "todo.updated";
    public static final String TASK_STARTED = "task.started";
    public static final String TASK_STEP_STARTED = "task.step.started";
    public static final String TASK_ASSISTANT_MESSAGE_COMPLETED = "task.assistant.message.completed";
    public static final String TASK_TOOL_CALL_STARTED = "task.tool.call.started";
    public static final String TASK_TOOL_CALL_COMPLETED = "task.tool.call.completed";
    public static final String TASK_TOOL_CALL_REJECTED = "task.tool.call.rejected";
    public static final String TASK_PERMISSION_REQUESTED = "task.permission.requested";
    public static final String TASK_PERMISSION_RESOLVED = "task.permission.resolved";
    public static final String TASK_COMPLETED = "task.completed";
    public static final String TASK_FAILED = "task.failed";
    public static final String TASK_KILLED = "task.killed";
    public static final String TASK_INTERRUPTED = "task.interrupted";

    public static final Set<String> TASK_TERMINALS = Set.of(
            TASK_COMPLETED, TASK_FAILED, TASK_KILLED, TASK_INTERRUPTED
    );

    public static final Set<String> RUN_TERMINALS = Set.of(
            RUN_COMPLETED, RUN_FAILED, RUN_CANCELLED, RUN_INTERRUPTED
    );

    private SessionJournalTypes() {
    }
}
