package cn.ayice.veyra.session.state;

/** 当前可见 Run 的持久化执行状态。 */
public record RunState(
        String runId,
        AgentPhase phase,
        int turnCount,
        int modelFailureCount,
        String transitionReason,
        String finalResponse
) {
}
