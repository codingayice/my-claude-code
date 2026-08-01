package cn.ayice.veyra.tooling.permission;

import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ValidationResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 工具调用的通用权限判断入口。它结合工具元数据、调用参数、权限模式、允许目录和会话规则给出决策。
 */
public final class PermissionChecker {

    /**
     * 工具类不允许实例化。
     */
    private PermissionChecker() {}

    /**
     * 所有工具调用的中心权限门。判断顺序很重要：缺失工具、输入校验、显式会话规则和当前权限模式依次决定最终结果。
     */
    public static PermissionDecision decide(
            BaseTool tool,
            ToolExecutionRequest request,
            PermissionContext context
    ) {
        if (tool == null) {
            return PermissionDecision.deny("Tool not found");
        }

        String args = request.arguments() == null ? "" : request.arguments();

        // 参数校验先于权限匹配，防止畸形路径或命令通过宽泛的会话规则。
        ValidationResult validation = tool.validateInput(args, context);
        if (!validation.valid()) {
            return PermissionDecision.deny("Invalid arguments: " + validation.message());
        }

        // 显式 DENY 和 ASK 具有最高优先级，不能被工具自身或自动批准模式覆盖。
        PermissionRule denyRule = context == null ? null : context.findToolWideRule(tool.name(), PermissionRule.PermissionBehavior.DENY);
        if (denyRule != null) {
            return PermissionDecision.deny("Matched deny rule", denyRule);
        }

        PermissionRule askRule = context == null ? null : context.findToolWideRule(tool.name(), PermissionRule.PermissionBehavior.ASK);
        if (askRule != null) {
            return PermissionDecision.ask("Matched ask rule", askRule);
        }

        // 文件类工具在自身权限检查中解析具体路径，因此必须在通用 allow 规则之前执行。
        PermissionDecision toolDecision = tool.checkPermissions(args, context);
        if (toolDecision.kind() == PermissionDecision.Kind.DENY) {
            return toolDecision;
        }
        if (toolDecision.kind() == PermissionDecision.Kind.ALLOW) {
            return toolDecision;
        }

        PermissionRule allowRule = context == null ? null : context.findToolWideRule(tool.name(), PermissionRule.PermissionBehavior.ALLOW);
        if (allowRule != null) {
            return PermissionDecision.allow("Matched allow rule", allowRule);
        }

        PermissionDecision modeDecision = decideByMode(tool, context);
        if (modeDecision != null) {
            return modeDecision;
        }

        if (toolDecision.kind() == PermissionDecision.Kind.ASK) {
            return toolDecision;
        }

        return PermissionDecision.ask("Default permission mode requires confirmation");
    }

    /**
     * 根据会话权限模式给出全局默认决策；无法决定时返回空值继续后续规则。
     */
    private static PermissionDecision decideByMode(BaseTool tool, PermissionContext context) {
        if (context == null || context.mode() == null) {
            return null;
        }
        return switch (context.mode()) {
            case AUTO_APPROVE -> PermissionDecision.allow("Auto approve mode allows tool execution");
            case ASK_EVERY_TIME, PROJECT_AUTO -> null;
        };
    }
}
