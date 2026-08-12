package cn.ayice.veyra.runtime.agent;

import java.util.Map;

/** Agent 状态机一次推进的统一结果。 */
public record AgentStepResult(String status, String reason, Map<String, Object> output) {
    public AgentStepResult {
        output = output == null ? Map.of() : Map.copyOf(output);
    }

    /** 创建正常终态结果。 */
    public static AgentStepResult completed(String content) {
        return new AgentStepResult("completed", "completed", Map.of("content", content == null ? "" : content));
    }

    /** 创建等待外部输入的挂起结果。 */
    public static AgentStepResult suspended(String reason, Map<String, Object> output) {
        return new AgentStepResult("suspended", reason, output);
    }

    /** 创建已经写入失败事实的推进结果。 */
    public static AgentStepResult failed(String reason, String content) {
        return new AgentStepResult("failed", reason, Map.of("content", content == null ? "" : content));
    }
}
