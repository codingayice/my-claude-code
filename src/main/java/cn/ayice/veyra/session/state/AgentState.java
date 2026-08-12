package cn.ayice.veyra.session.state;

import java.util.List;
import java.util.Map;

/** 一个 Run 路径的完整可持久化 Agent 状态。 */
public record AgentState(
        RunState run,
        List<MessageState> messages,
        Map<String, ToolCallState> toolCalls,
        Map<String, Map<String, Object>> approvals,
        List<Map<String, Object>> pendingInputs,
        List<Map<String, Object>> todos,
        Map<String, Map<String, Object>> tasks,
        Map<String, Object> contextSummary
) {
    public AgentState {
        messages = messages == null ? List.of() : List.copyOf(messages);
        toolCalls = toolCalls == null ? Map.of() : Map.copyOf(toolCalls);
        approvals = approvals == null ? Map.of() : Map.copyOf(approvals);
        pendingInputs = pendingInputs == null ? List.of() : List.copyOf(pendingInputs);
        todos = todos == null ? List.of() : List.copyOf(todos);
        tasks = tasks == null ? Map.of() : Map.copyOf(tasks);
        contextSummary = contextSummary == null ? Map.of() : Map.copyOf(contextSummary);
    }

    /** 创建尚无 Run 和路径事实的空状态。 */
    public static AgentState empty() {
        return new AgentState(null, List.of(), Map.of(), Map.of(), List.of(), List.of(), Map.of(), Map.of());
    }
}
