package cn.ayice.veyra.session;

/**
 * Read-only view of a tool approval waiting inside a session runtime.
 */
public record PendingApprovalState(
        String approvalId,
        String toolUseId,
        String tool,
        String arguments,
        String reason,
        ApprovalStatus status,
        String decision
) {
    public PendingApprovalState {
        status = status == null ? ApprovalStatus.PENDING : status;
        decision = decision == null ? "" : decision;
    }

    /** 审批事实的持久化生命周期。 */
    public enum ApprovalStatus { PENDING, RESOLVED }
}
