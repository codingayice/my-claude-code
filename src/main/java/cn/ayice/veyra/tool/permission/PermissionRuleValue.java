package cn.ayice.veyra.tool.permission;

/**
 * 权限规则的取值。它区分规则命中后的允许或拒绝结果。
 */
public record PermissionRuleValue(
        String toolName,
        String ruleContent
) {
}
