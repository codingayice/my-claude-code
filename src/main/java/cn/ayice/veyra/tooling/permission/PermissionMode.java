package cn.ayice.veyra.tooling.permission;

/**
 * 权限模式枚举。它决定工具调用是自动批准、项目内自动批准，还是每次都询问。
 */
public enum PermissionMode {
    ASK_EVERY_TIME,
    PROJECT_AUTO,
    AUTO_APPROVE;

    /**
     * 根据输入创建对应对象。
     */
    public static PermissionMode fromString(String raw) {
        if (raw == null || raw.isBlank()) {
            return ASK_EVERY_TIME;
        }
        return switch (raw.trim().toLowerCase()) {
            case "ask_every_time" -> ASK_EVERY_TIME;
            case "project_auto" -> PROJECT_AUTO;
            case "auto_approve" -> AUTO_APPROVE;
            default -> ASK_EVERY_TIME;
        };
    }

    /**
     * 返回写入配置文件时使用的权限模式值。
     */
    public String configValue() {
        return switch (this) {
            case ASK_EVERY_TIME -> "ask_every_time";
            case PROJECT_AUTO -> "project_auto";
            case AUTO_APPROVE -> "auto_approve";
        };
    }

    /**
     * 返回该模式是否默认允许工具执行。
     */
    public boolean allowsToolExecutionByDefault() {
        return switch (this) {
            case AUTO_APPROVE -> true;
            case ASK_EVERY_TIME, PROJECT_AUTO -> false;
        };
    }

    /**
     * 返回该模式是否默认允许只读工具执行。
     */
    public boolean allowsReadOnlyByDefault() {
        return switch (this) {
            case AUTO_APPROVE -> true;
            case ASK_EVERY_TIME, PROJECT_AUTO -> false;
        };
    }

    /**
     * 返回该模式是否应对未命中规则的工具调用请求审批。
     */
    public boolean shouldAskByDefault() {
        return switch (this) {
            case ASK_EVERY_TIME, PROJECT_AUTO -> true;
            case AUTO_APPROVE -> false;
        };
    }
}
