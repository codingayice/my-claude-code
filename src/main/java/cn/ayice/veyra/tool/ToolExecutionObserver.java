package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Lifecycle callbacks needed by execution surfaces with their own event protocol.
 */
public interface ToolExecutionObserver {

    ToolExecutionObserver NOOP = new ToolExecutionObserver() {
    };

    /**
     * 在权限策略完成初步授权判断后接收回调。
     */
    default void authorizationDecided(ToolExecutionRequest request, PermissionDecision decision) {
    }

    /**
     * 在工具调用需要用户审批时接收回调。
     */
    default void permissionRequested(ToolExecutionRequest request, PermissionDecision decision) {
    }

    /**
     * 在用户提交审批选择后接收回调。
     */
    default void permissionResolved(ToolExecutionRequest request, ToolExecutionConfirmation.Choice choice) {
    }
}
