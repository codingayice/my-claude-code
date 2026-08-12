package cn.ayice.veyra.session.persistence;

import java.util.Set;

/**
 * Session Event Store 的稳定领域事件名称。
 */
public final class SessionJournalTypes {
    public static final String SESSION_CREATED = "session.created";
    public static final String SESSION_SETTINGS_UPDATED = "session.settings.changed";
    public static final String CHECKPOINT_RESTORED = "checkpoint.restored";
    public static final String RUN_STARTED = "run.accepted";
    public static final String RUN_COMPLETED = "run.completed";
    public static final String RUN_FAILED = "run.failed";
    public static final String RUN_CANCELLED = "run.cancelled";
    public static final String RUN_INTERRUPTED = "run.interrupted";
    public static final String MODEL_CALL_STARTED = "model.call.started";
    public static final String MODEL_CALL_FAILED = "model.call.failed";
    public static final String MODEL_CALL_INTERRUPTED = "model.call.interrupted";
    public static final String USER_MESSAGE_RECORDED = "user.message.added";
    public static final String ASSISTANT_MESSAGE_RECORDED = "assistant.message.completed";
    public static final String CONTEXT_SUMMARY_RECORDED = "context.summary.updated";
    public static final String TOOL_CALL_STARTED = "tool.declared";
    public static final String TOOL_EXECUTION_STARTED = "tool.execution.started";
    public static final String TOOL_RESULT_RECORDED = "tool.execution.completed";
    public static final String PERMISSION_REQUESTED = "tool.approval.requested";
    public static final String PERMISSION_RESOLVED = "tool.approval.resolved";
    public static final String TODO_UPDATED = "todo.list.replaced";
    public static final String INPUT_QUEUED = "input.queued";
    public static final String INPUT_MODE_CHANGED = "input.mode.changed";
    public static final String INPUT_APPLIED = "input.applied";
    public static final String INPUT_CANCELLED = "input.cancelled";
    public static final String INPUT_FAILED = "input.failed";
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
