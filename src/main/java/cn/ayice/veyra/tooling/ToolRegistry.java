package cn.ayice.veyra.tooling;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型可见工具注册表。它维护可执行工具与模型规范的一致顺序，并支持按配置集过滤。
 */
public class ToolRegistry {

    /**
     * 工具配置集，按类别、可见性、风险等级及显式名单共同约束可用工具。
     */
    public record ToolProfile(
            String name,
            java.util.Set<BaseTool.Category> categories,
            java.util.Set<BaseTool.Visibility> visibilities,
            java.util.Set<BaseTool.RiskLevel> riskLevels,
            java.util.Set<String> allowedToolNames,
            java.util.Set<String> disallowedToolNames
    ) {
        public ToolProfile(
                String name,
                java.util.Set<BaseTool.Category> categories,
                java.util.Set<BaseTool.Visibility> visibilities,
                java.util.Set<BaseTool.RiskLevel> riskLevels
        ) {
            this(name, categories, visibilities, riskLevels, java.util.Set.of(), java.util.Set.of());
        }

        /**
         * 返回包含全部已注册工具的配置集。
         */
        public static ToolProfile all() {
            return new ToolProfile("all",
                    java.util.EnumSet.allOf(BaseTool.Category.class),
                    java.util.EnumSet.of(BaseTool.Visibility.ALL),
                    java.util.EnumSet.allOf(BaseTool.RiskLevel.class));
        }

        /**
         * 返回只允许读取和检索工具的配置集。
         */
        public static ToolProfile readOnly() {
            return named("read_only", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回计划类子 Agent 可使用的只读工具配置集。
         */
        public static ToolProfile plan() {
            return named("plan", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回代码探索子 Agent 可使用的工具配置集。
         */
        public static ToolProfile explore() {
            return named("explore", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回验证类子 Agent 可使用的工具配置集。
         */
        public static ToolProfile verify() {
            return named("verify", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回长期记忆任务专用的工具或权限策略。
         */
        public static ToolProfile memory() {
            return named("memory", "Memory");
        }

        /**
         * 返回子 Agent 通用工具与权限策略。
         */
        public static ToolProfile general() {
            return new ToolProfile("general",
                    java.util.EnumSet.allOf(BaseTool.Category.class),
                    java.util.EnumSet.of(BaseTool.Visibility.ALL),
                    java.util.EnumSet.allOf(BaseTool.RiskLevel.class));
        }

        /**
         * 按给定名称和工具名集合创建不可变工具配置集。
         */
        private static ToolProfile named(String name, String... toolNames) {
            return new ToolProfile(name,
                    java.util.EnumSet.allOf(BaseTool.Category.class),
                    java.util.EnumSet.of(BaseTool.Visibility.ALL),
                    java.util.EnumSet.allOf(BaseTool.RiskLevel.class),
                    java.util.Set.of(toolNames),
                    java.util.Set.of());
        }
    }

    private final List<ToolSpecification> specs = new ArrayList<>();

    private final Map<String, BaseTool> tools = new HashMap<>();

    /**
     * 注册组件并保持后续构建顺序稳定。
     */
    public void register(BaseTool tool) {
        specs.add(tool.getSpec());
        tools.put(tool.name(), tool);
    }

    /**
     * 返回按注册顺序排列的全部模型工具规范。
     */
    public List<ToolSpecification> getAllSpecs() {
        return new ArrayList<>(specs);
    }

    /**
     * 返回工具。
     */
    public BaseTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * 返回工具名到模型可见描述的只读映射。
     */
    public Map<String, String> getDescriptions() {
        Map<String, String> descriptions = new LinkedHashMap<>();
        for (ToolSpecification spec : specs) {
            BaseTool tool = tools.get(spec.name());
            if (tool != null) {
                descriptions.put(spec.name(), tool.description());
            }
        }
        return descriptions;
    }

    /**
     * 复制工具注册表并移除指定名称的工具及其模型规范。
     */
    public ToolRegistry without(String... names) {
        java.util.Set<String> toRemove = java.util.Set.of(names);
        ToolRegistry copy = new ToolRegistry();
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            if (!toRemove.contains(entry.getKey())) {
                copy.tools.put(entry.getKey(), entry.getValue());
            }
        }
        for (ToolSpecification spec : specs) {
            if (!toRemove.contains(spec.name())) {
                copy.specs.add(spec);
            }
        }
        return copy;
    }

    /**
     * 复制注册表并只保留配置集允许的工具及其模型规范。
     */
    public ToolRegistry profile(ToolProfile profile) {
        ToolRegistry copy = new ToolRegistry();
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            BaseTool tool = entry.getValue();
            if (matches(profile, entry.getKey(), tool)) {
                copy.tools.put(entry.getKey(), tool);
            }
        }
        for (ToolSpecification spec : specs) {
            BaseTool tool = tools.get(spec.name());
            if (tool != null && matches(profile, spec.name(), tool)) {
                copy.specs.add(spec);
            }
        }
        return copy;
    }

    /**
     * 判断工具是否满足配置集的显式名单、类别、可见性和风险等级约束。
     */
    public static boolean matches(ToolProfile profile, String toolName, BaseTool tool) {
        if (profile.allowedToolNames() != null && !profile.allowedToolNames().isEmpty()) {
            return profile.allowedToolNames().contains(toolName);
        }
        if (profile.disallowedToolNames() != null && profile.disallowedToolNames().contains(toolName)) {
            return false;
        }
        return profile.categories().contains(tool.category())
                && profile.visibilities().contains(tool.visibility())
                && profile.riskLevels().contains(tool.riskLevel());
    }

    /**
     * 处理并传播 {@code only} 对应的事件。
     */
    public ToolRegistry only(String... names) {
        java.util.Set<String> toKeep = java.util.Set.of(names);
        ToolRegistry copy = new ToolRegistry();
        for (Map.Entry<String, BaseTool> entry : tools.entrySet()) {
            if (toKeep.contains(entry.getKey())) {
                copy.tools.put(entry.getKey(), entry.getValue());
            }
        }
        for (ToolSpecification spec : specs) {
            if (toKeep.contains(spec.name())) {
                copy.specs.add(spec);
            }
        }
        return copy;
    }

}
