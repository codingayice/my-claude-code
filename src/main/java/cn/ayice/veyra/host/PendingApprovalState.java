package cn.ayice.veyra.host;

/**
 * Read-only view of a tool approval waiting inside a session runtime.
 */
public record PendingApprovalState(
        String approvalId,
        String tool,
        String arguments,
        String reason
) {
}
