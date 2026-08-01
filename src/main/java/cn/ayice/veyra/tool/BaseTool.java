package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import dev.langchain4j.agent.tool.ToolSpecification;

/**
 * 所有内置工具的抽象基类。工具通过它声明元数据、校验权限并暴露执行入口。
 */
public abstract class BaseTool {

    /**
     * 工具能力类别，用于权限策略和工具 profile 过滤。
     */
    public enum Category {
        FILESYSTEM,
        SHELL,
        SEARCH,
        ORCHESTRATION,
        BACKGROUND,
        UTILITY
    }

    /**
     * 工具对主 Agent、子 Agent 的可见范围。
     */
    public enum Visibility {
        MAIN,
        SUBAGENT,
        ALL
    }

    /**
     * 工具潜在副作用级别，用于默认审批决策。
     */
    public enum RiskLevel {
        SAFE,
        CAUTION,
        DANGEROUS
    }

    /**
     * 返回当前组件的稳定名称。
     */
    public abstract String name();

    /**
     * 返回当前组件面向模型或调用方的说明。
     */
    public abstract String description();

    /**
     * 返回工具所属类别。
     */
    public abstract Category category();

    /**
     * 返回工具的可见范围。
     */
    public abstract Visibility visibility();

    /**
     * 返回工具执行风险级别。
     */
    public abstract RiskLevel riskLevel();

    /**
     * 根据参数和权限上下文评估本次工具调用。
     */
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        return PermissionDecision.ask("工具调用需要确认");
    }

    /**
     * 校验工具输入并返回结构化校验结果。
     */
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        return ValidationResult.ok();
    }

    /**
     * 在不需要权限上下文时执行工具；未实现该入口的工具必须覆写上下文版本。
     */
    public ToolResult execute(String arguments) {
        throw new UnsupportedOperationException("工具尚未实现 execute");
    }

    /**
     * 使用权限上下文执行工具，默认委托给无上下文入口以兼容简单工具。
     */
    public ToolResult execute(String arguments, PermissionContext context) {
        return execute(arguments);
    }

    /**
     * 构建并返回工具调用规范。
     */
    public abstract ToolSpecification getSpec();
}
