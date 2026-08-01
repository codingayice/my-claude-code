package cn.ayice.veyra.kernel.subagent;

import cn.ayice.veyra.tooling.ToolRegistry;
import cn.ayice.veyra.tooling.permission.AgentPermissionPolicy;

/**
 * 某一种 agent 的不可变运行画像。它集中描述提示词、工具可见性、权限策略、轮数限制、后台默认值和可观测记录开关。
 */
public record AgentProfile(
        String type,
        String displayName,
        String systemPrompt,
        ToolRegistry.ToolProfile toolProfile,
        AgentPermissionPolicy permissionPolicy,
        int maxTurns,
        boolean backgroundByDefault,
        boolean recordTranscript
) {
}
