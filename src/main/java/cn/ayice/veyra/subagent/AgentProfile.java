package cn.ayice.veyra.subagent;

import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.tool.ToolExecutionPolicy;
import cn.ayice.veyra.tool.permission.PermissionChecker;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionMode;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 某一种 Agent 的不可变运行画像，并集中提供内置画像和其权限策略。
 */
public record AgentProfile(
        String type,
        String displayName,
        String systemPrompt,
        ToolCatalog.ToolProfile toolProfile,
        PermissionPolicy permissionPolicy,
        int maxTurns,
        boolean backgroundByDefault,
        boolean recordTranscript
) {

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
     * 根据用户输入和最大轮数创建内置子 Agent 画像。
     */
    public static AgentProfile fromType(String rawType, int configuredMaxRounds) {
        String normalized = normalize(rawType);
        int maxTurns = Math.max(configuredMaxRounds, 0);
        return switch (normalized) {
            case "Explore" -> new AgentProfile(
                    "Explore", "Explore", """
                    You are an Explore subagent. Investigate the codebase and report facts.
                    You can read files, search code, and use Bash only for read-only operations.
                    Do not create, edit, delete, install, or run commands that change project state.
                    Return a concise structured report in Chinese.
                    """, ToolCatalog.ToolProfile.explore(), PermissionPolicy.readOnly(), maxTurns, false, true
            );
            case "Plan" -> new AgentProfile(
                    "Plan", "Plan", """
                    You are a Plan subagent. Explore the codebase and produce an implementation plan.
                    You can read files, search code, and use Bash only for read-only operations.
                    Do not create, edit, delete, install, or run commands that change project state.
                    Return concrete steps, risks, and likely files to modify in Chinese.
                    """, ToolCatalog.ToolProfile.plan(), PermissionPolicy.readOnly(), maxTurns, false, true
            );
            case "verification" -> new AgentProfile(
                    "verification", "Verification", """
                    You are a Verification subagent. Check completed work and identify risks.
                    Use real read-only checks when possible. Do not modify project files, install dependencies, or run git write operations.
                    Report findings by severity in Chinese and end with VERDICT: PASS, VERDICT: FAIL, or VERDICT: PARTIAL.
                    """, ToolCatalog.ToolProfile.verify(), PermissionPolicy.readOnly(), maxTurns, false, true
            );
            case "memory-extraction" -> memoryExtraction(maxTurns);
            default -> new AgentProfile(
                    "general-purpose", "General", """
                    You are a general-purpose subagent. Complete the assigned task independently.
                    Stay within the prompt scope and do not start another agent. Report the result in Chinese.
                    """, ToolCatalog.ToolProfile.general(), PermissionPolicy.general(), maxTurns, false, true
            );
        };
    }

    /**
     * 创建仅允许执行记忆提取所需工具的内部 Agent 画像。
     */
    public static AgentProfile memoryExtraction(int configuredMaxTurns) {
        int maxTurns = configuredMaxTurns <= 0 ? 5 : Math.min(configuredMaxTurns, 5);
        return new AgentProfile(
                "memory-extraction", "Memory Extraction", """
                You are a memory extraction subagent.
                Maintain durable long-term memory markdown files only when recent conversation contains stable cross-session facts.
                Do not save temporary task state, compact summaries, code structure, file inventories, git history, or facts derivable from the repository.
                If no durable memory should be saved, make no file changes and reply briefly.
                """, ToolCatalog.ToolProfile.memory(), PermissionPolicy.memory(), maxTurns, true, false
        );
    }

    /**
     * 将外部类型名称规范化为内置画像键。
     */
    private static String normalize(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            return "general-purpose";
        }
        String raw = rawType.trim();
        if ("Explore".equals(raw) || "Plan".equals(raw) || "verification".equals(raw)
                || "general-purpose".equals(raw) || "memory-extraction".equals(raw)) {
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

    /**
     * 绑定在画像上的子 Agent 权限策略。
     */
    public record PermissionPolicy(
            Set<String> allowedTools,
            boolean readOnlyBash,
            boolean canAskPermission,
            PermissionMode permissionModeOverride
    ) implements ToolExecutionPolicy {
        public PermissionPolicy {
            allowedTools = Collections.unmodifiableSet(
                    new LinkedHashSet<>(allowedTools == null ? Set.of() : allowedTools)
            );
        }

        /**
         * 判断工具名是否位于该 Agent 的允许集合中。
         */
        public boolean allowsTool(String toolName) {
            return toolName != null && allowedTools.contains(toolName);
        }

        /**
         * 根据工具、参数和当前上下文计算权限决定。
         */
        @Override
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
         * 返回只读子 Agent 权限策略。
         */
        public static PermissionPolicy readOnly() {
            return new PermissionPolicy(
                    Set.of("Read", "Glob", "Grep", "bash"), true, false, PermissionMode.ASK_EVERY_TIME
            );
        }

        /**
         * 返回通用子 Agent 权限策略。
         */
        public static PermissionPolicy general() {
            return new PermissionPolicy(
                    Set.of("Read", "Edit", "Write", "Glob", "Grep", "bash"), false, true, null
            );
        }

        /**
         * 返回长期记忆提取 Agent 权限策略。
         */
        public static PermissionPolicy memory() {
            return new PermissionPolicy(Set.of("Memory"), false, false, PermissionMode.PROJECT_AUTO);
        }
    }
}
