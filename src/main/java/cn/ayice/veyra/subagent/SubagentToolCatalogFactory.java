package cn.ayice.veyra.subagent;

import cn.ayice.veyra.tool.ToolCatalog;

/**
 * Boot 为每次子 Agent 执行创建 Profile 限定工具目录的装配回调。
 */
@FunctionalInterface
public interface SubagentToolCatalogFactory {

    /**
     * 为给定 Agent Profile 创建独占工具目录和状态缓存。
     */
    ToolCatalog create(AgentProfile profile);
}
