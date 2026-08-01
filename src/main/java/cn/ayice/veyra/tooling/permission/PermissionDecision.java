package cn.ayice.veyra.tooling.permission;

/**
 * 一次权限判断的结果。ALLOW、DENY 和 ASK 都带有可展示给用户或模型的原因。
 */
public record PermissionDecision(
        Kind kind,
        String reason,
        PermissionRule matchedRule
) {
    /**
     * Kind 枚举对应流程允许的离散状态。
     */
    public enum Kind {
        ALLOW,
        ASK,
        DENY
    }

    /**
     * 创建允许执行的权限决定。
     */
    public static PermissionDecision allow(String reason) {
        return new PermissionDecision(Kind.ALLOW, reason, null);
    }

    /**
     * 创建需要用户确认的权限决定。
     */
    public static PermissionDecision ask(String reason) {
        return new PermissionDecision(Kind.ASK, reason, null);
    }

    /**
     * 创建拒绝执行的权限决定。
     */
    public static PermissionDecision deny(String reason) {
        return new PermissionDecision(Kind.DENY, reason, null);
    }

    /**
     * 创建允许执行的权限决定。
     */
    public static PermissionDecision allow(String reason, PermissionRule rule) {
        return new PermissionDecision(Kind.ALLOW, reason, rule);
    }

    /**
     * 创建需要用户确认的权限决定。
     */
    public static PermissionDecision ask(String reason, PermissionRule rule) {
        return new PermissionDecision(Kind.ASK, reason, rule);
    }

    /**
     * 创建拒绝执行的权限决定。
     */
    public static PermissionDecision deny(String reason, PermissionRule rule) {
        return new PermissionDecision(Kind.DENY, reason, rule);
    }
}
