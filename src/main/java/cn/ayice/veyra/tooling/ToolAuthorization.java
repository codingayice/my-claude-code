package cn.ayice.veyra.tooling;

import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionDecision;
import cn.ayice.veyra.tooling.ToolExecutionConfirmation;
import cn.ayice.veyra.tooling.BaseTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Result of the lookup, validation, permission, and approval stages for one tool request.
 */
public record ToolAuthorization(
        ToolExecutionRequest request,
        BaseTool tool,
        PermissionDecision decision,
        ToolExecutionConfirmation.Choice choice,
        PermissionContext context,
        String rejectionReason
) {

    /**
     * 返回本次工具授权是否允许继续执行。
     */
    public boolean allowed() {
        return rejectionReason == null;
    }
}
