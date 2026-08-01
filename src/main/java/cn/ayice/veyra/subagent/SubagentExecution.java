package cn.ayice.veyra.subagent;

import cn.ayice.veyra.tool.permission.PermissionContext;

/**
 * 子 Agent 执行回调，隔离任务管理与具体运行时实现。
 */
@FunctionalInterface
public interface SubagentExecution {

    /**
     * 执行当前运行策略并返回最终结果。
     */
    AgentRunResult run(
            String prompt,
            String subagentType,
            PermissionContext parentPermissionContext,
            String agentId,
            String description
    );
}
