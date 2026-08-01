package cn.ayice.veyra.control.service;

import cn.ayice.veyra.host.RuntimeHost;
import cn.ayice.veyra.control.dto.approval.ApprovalListResponse;
import cn.ayice.veyra.control.dto.approval.PendingApprovalResponse;
import org.springframework.stereotype.Service;

/**
 * 工具权限审批应用服务。
 */
@Service
public class ApprovalApplicationService {

    private final RuntimeHost runtimeHost;

    /**
     * 注入该服务运行所需依赖并创建 ApprovalApplicationService。
     */
    public ApprovalApplicationService(RuntimeHost runtimeHost) {
        this.runtimeHost = runtimeHost;
    }

    /**
     * 返回当前会话尚未处理的工具审批快照。
     */
    public ApprovalListResponse pendingApprovals(String sessionId) {
        var items = runtimeHost.pendingApprovals(sessionId).stream()
                .map(approval -> new PendingApprovalResponse(
                        approval.approvalId(),
                        approval.tool(),
                        approval.arguments(),
                        approval.reason()
                ))
                .toList();
        return new ApprovalListResponse(items);
    }

    /**
     * 根据工具、参数和当前权限上下文计算允许、询问或拒绝决定。
     */
    public boolean decide(String sessionId, String approvalId, String decision) {
        return runtimeHost.resolveApproval(sessionId, approvalId, decision);
    }
}
