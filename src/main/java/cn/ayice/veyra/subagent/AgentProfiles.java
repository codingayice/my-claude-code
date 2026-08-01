package cn.ayice.veyra.subagent;

import cn.ayice.veyra.tool.ToolRegistry;
import cn.ayice.veyra.tool.permission.AgentPermissionPolicy;

import java.util.Locale;

/**
 * 内置 AgentProfile 的工厂。用户可见 agent 和 memory-extraction 这类内部 agent 都在这里定义，避免运行时出现特殊分支。
 */
public final class AgentProfiles {

    private AgentProfiles() {
    }

    /**
     * 判断用户传入的子 Agent 类型是否在公开白名单中。
     */
    public static boolean isUserAllowedType(String rawType) {
        return rawType != null
                && ("Explore".equals(rawType)
                || "Plan".equals(rawType)
                || "verification".equals(rawType)
                || "general-purpose".equals(rawType));
    }

    /**
     * 根据输入创建对应对象。
     */
    public static AgentProfile fromType(String rawType, int configuredMaxRounds) {
        String normalized = normalize(rawType);
        int maxTurns = Math.max(configuredMaxRounds, 0);
        return switch (normalized) {
            case "Explore" -> new AgentProfile(
                    "Explore",
                    "Explore",
                    """
                    You are an Explore subagent. Investigate the codebase and report facts.
                    You can read files, search code, and use Bash only for read-only operations.
                    Do not create, edit, delete, install, or run commands that change project state.
                    Return a concise structured report in Chinese.
                    """,
                    ToolRegistry.ToolProfile.explore(),
                    AgentPermissionPolicy.readOnly(),
                    maxTurns,
                    false,
                    true
            );
            case "Plan" -> new AgentProfile(
                    "Plan",
                    "Plan",
                    """
                    You are a Plan subagent. Explore the codebase and produce an implementation plan.
                    You can read files, search code, and use Bash only for read-only operations.
                    Do not create, edit, delete, install, or run commands that change project state.
                    Return concrete steps, risks, and likely files to modify in Chinese.
                    """,
                    ToolRegistry.ToolProfile.plan(),
                    AgentPermissionPolicy.readOnly(),
                    maxTurns,
                    false,
                    true
            );
            case "verification" -> new AgentProfile(
                    "verification",
                    "Verification",
                    """
                    You are a Verification subagent. Check completed work and identify risks.
                    Use real read-only checks when possible. Do not modify project files, install dependencies, or run git write operations.
                    Report findings by severity in Chinese and end with VERDICT: PASS, VERDICT: FAIL, or VERDICT: PARTIAL.
                    """,
                    ToolRegistry.ToolProfile.verify(),
                    AgentPermissionPolicy.readOnly(),
                    maxTurns,
                    false,
                    true
            );
            case "memory-extraction" -> memoryExtraction(maxTurns);
            default -> new AgentProfile(
                    "general-purpose",
                    "General",
                    """
                    You are a general-purpose subagent. Complete the assigned task independently.
                    Stay within the prompt scope and do not start another agent. Report the result in Chinese.
                    """,
                    ToolRegistry.ToolProfile.general(),
                    AgentPermissionPolicy.general(),
                    maxTurns,
                    false,
                    true
            );
        };
    }

    /**
     * 创建仅允许执行记忆提取所需工具的子 Agent 配置。
     */
    public static AgentProfile memoryExtraction(int configuredMaxTurns) {
        int maxTurns = configuredMaxTurns <= 0 ? 5 : Math.min(configuredMaxTurns, 5);
        return new AgentProfile(
                "memory-extraction",
                "Memory Extraction",
                """
                You are a memory extraction subagent.
                Maintain durable long-term memory markdown files only when recent conversation contains stable cross-session facts.
                Do not save temporary task state, compact summaries, code structure, file inventories, git history, or facts derivable from the repository.
                If no durable memory should be saved, make no file changes and reply briefly.
                """,
                ToolRegistry.ToolProfile.memory(),
                AgentPermissionPolicy.memory(),
                maxTurns,
                true,
                false
        );
    }

    /**
     * 将输入转换为模块内部使用的统一形式。
     */
    private static String normalize(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "general-purpose";
        }
        String raw = rawType.trim();
        if ("Explore".equals(raw) || "Plan".equals(raw) || "verification".equals(raw) || "general-purpose".equals(raw)
                || "memory-extraction".equals(raw)) {
            return raw;
        }
        String value = raw.toLowerCase(Locale.ROOT).replace('_', '-');
        return switch (value) {
            case "general", "general-purpose", "generalpurpose" -> "general-purpose";
            case "explore" -> "Explore";
            case "plan" -> "Plan";
            case "verify", "verification" -> "verification";
            case "memory", "memory-extraction", "memoryextraction" -> "memory-extraction";
            default -> throw new IllegalArgumentException("Unsupported subagent_type: " + rawType
                    + ". Allowed values: Explore, Plan, verification, general-purpose");
        };
    }
}
