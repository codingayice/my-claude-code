package cn.ayice.veyra.tool.permission;


/**
 * 细粒度权限规则。它描述后续工具调用可以复用的范围化允许或拒绝规则。
 */
public record PermissionRule (
        String source,
        PermissionBehavior ruleBehavior,
        Value ruleValue
) {
    /**
     * 权限规则可以产生的允许、询问或拒绝行为。
     */
    public enum PermissionBehavior {
        ALLOW,
        DENY,
        ASK
    }

    /**
     * 创建用于逐步填写字段的空构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder 按步骤构建目标对象。
     */
    public static class Builder {
        private String source;
        private PermissionBehavior behavior;
        private String toolName;
        private String ruleContent;

        /**
         * 设置权限规则来源并返回当前构建器。
         */
        public Builder source(String source) {
            this.source = source;
            return this;
        }

        /**
         * 设置权限规则行为并返回当前构建器。
         */
        public Builder behavior(PermissionBehavior behavior) {
            this.behavior = behavior;
            return this;
        }

        /**
         * 创建允许执行的权限决定。
         */
        public Builder allow() {
            this.behavior = PermissionBehavior.ALLOW;
            return this;
        }

        /**
         * 创建拒绝执行的权限决定。
         */
        public Builder deny() {
            this.behavior = PermissionBehavior.DENY;
            return this;
        }

        /**
         * 创建需要用户确认的权限决定。
         */
        public Builder ask() {
            this.behavior = PermissionBehavior.ASK;
            return this;
        }

        /**
         * 设置权限规则目标工具名并返回当前构建器。
         */
        public Builder tool(String toolName) {
            this.toolName = toolName;
            return this;
        }

        /**
         * 设置权限规则匹配内容并返回当前构建器。
         */
        public Builder content(String ruleContent) {
            this.ruleContent = ruleContent;
            return this;
        }

        /**
         * 根据当前输入构建目标对象。
         */
        public PermissionRule build() {
            if (behavior == null) {
                throw new IllegalStateException("behavior 是必填字段");
            }
            return new PermissionRule(source, behavior, new Value(toolName, ruleContent));
        }
    }

    /**
     * 返回权限规则限定的工具名；规则值缺失时返回 null。
     */
    public String toolName() {
        return ruleValue == null ? null : ruleValue.toolName();
    }

    /**
     * 返回去除工具名前缀后的规则匹配内容。
     */
    public String ruleContent() {
        return ruleValue == null ? null : ruleValue.ruleContent();
    }

    /**
     * 判断规则是否覆盖某工具的全部调用而非特定参数。
     */
    public boolean isToolWideRule() {
        return ruleValue.ruleContent() == null || ruleValue.ruleContent().isBlank();
    }

    /**
     * 权限规则限定的工具名和参数匹配内容。
     */
    public record Value(String toolName, String ruleContent) {
    }
}
