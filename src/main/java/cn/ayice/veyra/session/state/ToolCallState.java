package cn.ayice.veyra.session.state;

/** 一个模型 ToolUse 的完整持久化状态。 */
public record ToolCallState(
        String toolUseId,
        String name,
        String arguments,
        ToolCallPhase phase,
        String approvalId,
        ToolOutcome outcome,
        String resultContent,
        String recoveryPolicy
) {
}
