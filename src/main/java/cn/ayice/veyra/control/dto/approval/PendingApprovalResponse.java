package cn.ayice.veyra.control.dto.approval;

/**
 * 前端待确认的工具授权请求。
 */
public record PendingApprovalResponse(
        String approvalId,
        String tool,
        String arguments,
        String reason
) {
}
