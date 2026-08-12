package cn.ayice.veyra.session.state;

import cn.ayice.veyra.session.persistence.SessionJournalEntry;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 从当前可见 Run 路径事件纯函数构建 AgentState。 */
public final class SessionProjection {

    /** 从空状态投影完整可见路径。 */
    public AgentState reduceAgent(List<SessionJournalEntry> events) {
        return reduceAgent(AgentState.empty(), events);
    }

    /** 从已有 Snapshot 基线继续应用尾部事件。 */
    public AgentState reduceAgent(AgentState initial, List<SessionJournalEntry> events) {
        List<MessageState> messages = new ArrayList<>(initial.messages());
        Map<String, ToolCallState> tools = new LinkedHashMap<>(initial.toolCalls());
        Map<String, Map<String, Object>> approvals = new LinkedHashMap<>(initial.approvals());
        List<Map<String, Object>> pending = new ArrayList<>(initial.pendingInputs());
        List<Map<String, Object>> todos = new ArrayList<>(initial.todos());
        Map<String, Map<String, Object>> tasks = new LinkedHashMap<>(initial.tasks());
        Map<String, Object> summary = new LinkedHashMap<>(initial.contextSummary());
        RunState run = initial.run();

        for (SessionJournalEntry event : events) {
            Map<String, Object> data = event.payload();
            switch (event.type()) {
                case SessionJournalTypes.RUN_STARTED -> run = new RunState(
                        event.runId(), AgentPhase.READY_FOR_MODEL, 1, 0, "accepted", "");
                case SessionJournalTypes.MODEL_CALL_STARTED -> {
                    if (run != null) run = new RunState(run.runId(), AgentPhase.CALLING_MODEL,
                            run.turnCount(), run.modelFailureCount(), "model_call_started", run.finalResponse());
                }
                case SessionJournalTypes.MODEL_CALL_FAILED -> {
                    if (run != null) run = new RunState(run.runId(), AgentPhase.READY_FOR_MODEL,
                            run.turnCount(), run.modelFailureCount() + 1,
                            text(data, "errorCode", "model_call_failed"), run.finalResponse());
                }
                case SessionJournalTypes.MODEL_CALL_INTERRUPTED -> {
                    if (run != null) run = new RunState(run.runId(), AgentPhase.READY_FOR_MODEL,
                            run.turnCount(), run.modelFailureCount(), "model_call_interrupted", run.finalResponse());
                }
                case SessionJournalTypes.USER_MESSAGE_RECORDED -> messages.add(new MessageState(
                        text(data, "messageId", event.eventId()), event.sequence(), event.runId(), MessageRole.USER,
                        text(data, "text", ""), "", !Boolean.FALSE.equals(data.get("visible")), List.of()));
                case SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED -> {
                    List<ToolCallState> declared = decodeToolCalls(data);
                    declared.forEach(tool -> tools.putIfAbsent(tool.toolUseId(), tool));
                    messages.add(new MessageState(text(data, "messageId", event.eventId()), event.sequence(),
                            event.runId(), MessageRole.ASSISTANT, text(data, "text", ""),
                            text(data, "thinking", ""), true, declared));
                    if (run != null) {
                        run = new RunState(run.runId(), AgentPhase.MODEL_RESULT_RECORDED,
                                run.turnCount(), 0, "model_result_recorded", run.finalResponse());
                    }
                }
                case SessionJournalTypes.TOOL_EXECUTION_STARTED -> updateTool(tools, data,
                        ToolCallPhase.EXECUTION_STARTED, null, "");
                case SessionJournalTypes.TOOL_RESULT_RECORDED -> {
                    ToolOutcome outcome = outcome(data);
                    updateTool(tools, data, ToolCallPhase.RESULT_RECORDED, outcome, text(data, "content", ""));
                    messages.add(new MessageState(event.eventId(), event.sequence(), event.runId(), MessageRole.TOOL,
                            text(data, "content", ""), "", false, List.of()));
                }
                case SessionJournalTypes.PERMISSION_REQUESTED -> approvals.put(
                        text(data, "approvalId", event.eventId()), Map.copyOf(data));
                case SessionJournalTypes.PERMISSION_RESOLVED, SessionJournalTypes.PERMISSION_INTERRUPTED -> {
                    String id = text(data, "approvalId", "");
                    if (!id.isBlank()) approvals.remove(id);
                }
                case SessionJournalTypes.INPUT_QUEUED -> pending.add(Map.copyOf(data));
                case SessionJournalTypes.INPUT_MODE_CHANGED -> replacePending(pending, data);
                case SessionJournalTypes.INPUT_APPLIED, SessionJournalTypes.INPUT_CANCELLED,
                     SessionJournalTypes.INPUT_FAILED -> pending.removeIf(item ->
                        text(item, "messageId", "").equals(text(data, "messageId", "")));
                case SessionJournalTypes.TODO_UPDATED -> {
                    todos.clear();
                    Object items = data.get("items");
                    if (items instanceof List<?> list) list.stream().filter(Map.class::isInstance)
                            .map(item -> (Map<String, Object>) item).map(Map::copyOf).forEach(todos::add);
                }
                case SessionJournalTypes.TASK_STARTED, SessionJournalTypes.TASK_STEP_STARTED,
                     SessionJournalTypes.TASK_ASSISTANT_MESSAGE_COMPLETED, SessionJournalTypes.TASK_TOOL_CALL_STARTED,
                     SessionJournalTypes.TASK_TOOL_CALL_COMPLETED, SessionJournalTypes.TASK_TOOL_CALL_REJECTED,
                     SessionJournalTypes.TASK_PERMISSION_REQUESTED, SessionJournalTypes.TASK_PERMISSION_RESOLVED ->
                        tasks.put(text(data, "taskId", event.eventId()), Map.copyOf(data));
                case SessionJournalTypes.TASK_COMPLETED, SessionJournalTypes.TASK_FAILED,
                     SessionJournalTypes.TASK_KILLED, SessionJournalTypes.TASK_INTERRUPTED ->
                        tasks.put(text(data, "taskId", event.eventId()), Map.copyOf(data));
                case SessionJournalTypes.CONTEXT_SUMMARY_RECORDED -> {
                    summary.clear();
                    summary.putAll(data);
                }
                case SessionJournalTypes.RUN_COMPLETED, SessionJournalTypes.RUN_FAILED,
                     SessionJournalTypes.RUN_CANCELLED, SessionJournalTypes.RUN_INTERRUPTED -> {
                    if (run != null) run = new RunState(run.runId(), terminalPhase(event.type()), run.turnCount(),
                            run.modelFailureCount(), text(data, "reason", event.type()), text(data, "content", ""));
                }
                default -> { }
            }
        }
        return new AgentState(run, messages, tools, approvals, pending, todos, tasks, summary);
    }

    /** 解码 Assistant 事件声明的工具列表。 */
    private static List<ToolCallState> decodeToolCalls(Map<String, Object> data) {
        Object raw = data.get("toolCalls");
        if (!(raw instanceof List<?> list)) return List.of();
        List<ToolCallState> result = new ArrayList<>();
        for (Object item : list) if (item instanceof Map<?, ?> map) result.add(new ToolCallState(
                value(map, "id"), value(map, "name"), value(map, "arguments"),
                ToolCallPhase.DECLARED, null, null, "", "UNKNOWN"));
        return List.copyOf(result);
    }

    /** 单调更新一个工具调用状态。 */
    private static void updateTool(Map<String, ToolCallState> tools, Map<String, Object> data,
                                   ToolCallPhase phase, ToolOutcome outcome, String content) {
        String id = text(data, "toolUseId", "");
        ToolCallState previous = tools.get(id);
        if (id.isBlank()) return;
        tools.put(id, new ToolCallState(id, text(data, "name", previous == null ? "" : previous.name()),
                previous == null ? "{}" : previous.arguments(), phase,
                previous == null ? null : previous.approvalId(), outcome, content,
                previous == null ? "UNKNOWN" : previous.recoveryPolicy()));
    }

    /** 将 Run 终态事件映射为领域阶段。 */
    private static AgentPhase terminalPhase(String type) {
        return switch (type) {
            case SessionJournalTypes.RUN_COMPLETED -> AgentPhase.TERMINAL_COMPLETED;
            case SessionJournalTypes.RUN_CANCELLED -> AgentPhase.TERMINAL_CANCELLED;
            case SessionJournalTypes.RUN_INTERRUPTED -> AgentPhase.TERMINAL_INTERRUPTED;
            default -> AgentPhase.TERMINAL_FAILED;
        };
    }

    /** 安全解析工具结果。 */
    private static ToolOutcome outcome(Map<String, Object> data) {
        try { return ToolOutcome.valueOf(text(data, "outcome", "FAILED")); }
        catch (IllegalArgumentException ignored) { return ToolOutcome.FAILED; }
    }

    /** 读取字符串键值。 */
    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key); return value == null ? fallback : String.valueOf(value);
    }
    /** 读取任意 Map 键值。 */
    private static String value(Map<?, ?> map, String key) {
        Object value = map.get(key); return value == null ? "" : String.valueOf(value);
    }

    /** 用相同 messageId 的新模式替换待处理输入。 */
    private static void replacePending(List<Map<String, Object>> pending, Map<String, Object> update) {
        String id = text(update, "messageId", "");
        for (int index = 0; index < pending.size(); index++) {
            Map<String, Object> item = pending.get(index);
            if (id.equals(text(item, "messageId", ""))) {
                Map<String, Object> next = new LinkedHashMap<>(item);
                next.putAll(update);
                pending.set(index, Map.copyOf(next));
                return;
            }
        }
    }
}
