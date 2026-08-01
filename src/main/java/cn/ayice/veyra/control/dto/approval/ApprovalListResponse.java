package cn.ayice.veyra.control.dto.approval;

import java.util.List;

/**
 * 当前会话所有待处理授权请求。
 */
public record ApprovalListResponse(List<PendingApprovalResponse> items) {
}
