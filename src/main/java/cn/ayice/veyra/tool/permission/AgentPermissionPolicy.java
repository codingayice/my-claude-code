package cn.ayice.veyra.tool.permission;

import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolExecutionPolicy;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 绑定在 AgentProfile 上的子 agent 权限策略。SubagentRuntime 通过它把 profile 的工具白名单和通用 PermissionChecker 合并成最终判断。
 */
public record AgentPermissionPolicy(
        Set<String> allowedTools,
        boolean readOnlyBash,
        boolean canAskPermission,
        PermissionMode permissionModeOverride
) implements ToolExecutionPolicy {

    public AgentPermissionPolicy {
        allowedTools = Collections.unmodifiableSet(new LinkedHashSet<>(allowedTools == null ? Set.of() : allowedTools));
    }

    /**
     * 判断工具名是否位于该 Agent 的允许集合中。
     */
    public boolean allowsTool(String toolName) {
        return toolName != null && allowedTools.contains(toolName);
    }

    /**
     * 根据工具、参数和当前权限上下文计算允许、询问或拒绝决定。
     */
    public PermissionDecision decide(BaseTool tool, ToolExecutionRequest request, PermissionContext context) {
        if (tool == null || request == null || !allowsTool(request.name())) {
            return PermissionDecision.deny("Subagent policy does not allow this tool");
        }
        return PermissionChecker.decide(tool, request, context);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String deniedApprovalReason(PermissionDecision decision) {
        return "Subagent policy does not allow requesting extra permissions: " + decision.reason();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String emptySuccessContent() {
        return "<success>工具已成功执行</success>";
    }

    /**
     * 返回只允许读取和检索工具的配置集。
     */
    public static AgentPermissionPolicy readOnly() {
        return new AgentPermissionPolicy(
                Set.of("Read", "Glob", "Grep", "bash"),
                true,
                false,
                PermissionMode.ASK_EVERY_TIME
        );
    }

    /**
     * 返回子 Agent 通用工具与权限策略。
     */
    public static AgentPermissionPolicy general() {
        return new AgentPermissionPolicy(
                Set.of("Read", "Edit", "Write", "Glob", "Grep", "bash"),
                false,
                true,
                null
        );
    }

    /**
     * 返回长期记忆任务专用的工具或权限策略。
     */
    public static AgentPermissionPolicy memory() {
        return new AgentPermissionPolicy(
                Set.of("Memory"),
                false,
                false,
                PermissionMode.PROJECT_AUTO
        );
    }
}
