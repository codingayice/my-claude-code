package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.state.FileStateCache;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型可见规范与可执行实例的唯一有序工具目录。
 */
public final class ToolCatalog {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalog.class);

    private final Map<String, BaseTool> tools;
    private final FileStateCache fileStateCache;

    private ToolCatalog(Map<String, BaseTool> tools, FileStateCache fileStateCache) {
        this.tools = Collections.unmodifiableMap(new LinkedHashMap<>(tools));
        this.fileStateCache = fileStateCache;
    }

    /**
     * 使用一组工具创建唯一目录，后注册的同名工具覆盖前值并保持确定顺序。
     */
    public static ToolCatalog create(List<? extends BaseTool> tools, FileStateCache fileStateCache) {
        LinkedHashMap<String, BaseTool> ordered = new LinkedHashMap<>();
        for (BaseTool tool : tools) {
            ordered.put(tool.name(), tool);
        }
        return new ToolCatalog(ordered, fileStateCache);
    }

    /**
     * 返回只保留指定 Profile 可见工具的独立目录视图。
     */
    public ToolCatalog profile(ToolProfile profile) {
        LinkedHashMap<String, BaseTool> selected = new LinkedHashMap<>();
        tools.forEach((name, tool) -> {
            if (matches(profile, name, tool)) {
                selected.put(name, tool);
            }
        });
        return new ToolCatalog(selected, fileStateCache);
    }

    /**
     * 返回按目录顺序排列的模型工具规范。
     */
    public List<ToolSpecification> specifications() {
        return tools.values().stream().map(BaseTool::getSpec).toList();
    }

    /**
     * 返回工具名到模型可见描述的有序只读映射。
     */
    public Map<String, String> descriptions() {
        LinkedHashMap<String, String> descriptions = new LinkedHashMap<>();
        tools.forEach((name, tool) -> descriptions.put(name, tool.description()));
        return Collections.unmodifiableMap(descriptions);
    }

    /**
     * 按稳定名称定位工具，未注册时返回空值。
     */
    public BaseTool find(String name) {
        return tools.get(name);
    }

    /**
     * 执行目录中的工具，并将未知工具和实现异常归一化为失败结果。
     */
    public ToolResult execute(ToolExecutionRequest request, PermissionContext context) {
        BaseTool tool = tools.get(request.name());
        if (tool == null) {
            return ToolResult.error("未找到工具[" + request.name() + "]");
        }
        try {
            ToolResult result = context == null
                    ? tool.execute(request.arguments())
                    : tool.execute(request.arguments(), context);
            if (!result.success()) {
                log.warn("tool returned failure toolUseId={} tool={} content={}",
                        request.id(), request.name(), abbreviate(result.content()));
            }
            return result;
        } catch (Exception error) {
            log.error("tool execution failed toolUseId={} tool={}", request.id(), request.name(), error);
            return ToolResult.error("工具执行失败，原因是 " + error.getMessage());
        }
    }

    /**
     * 返回该工具集合独占的文件状态缓存。
     */
    public FileStateCache fileStateCache() {
        return fileStateCache;
    }

    /**
     * 判断工具是否满足显式名单、类别、可见性和风险等级约束。
     */
    static boolean matches(ToolProfile profile, String toolName, BaseTool tool) {
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
     * 截断日志中的工具失败内容。
     */
    private static String abbreviate(String content) {
        if (content == null || content.length() <= 500) {
            return content;
        }
        return content.substring(0, 500) + "...";
    }

    /**
     * 按类别、可见性、风险等级及显式名单共同约束可用工具的配置集。
     */
    public record ToolProfile(
            String name,
            Set<BaseTool.Category> categories,
            Set<BaseTool.Visibility> visibilities,
            Set<BaseTool.RiskLevel> riskLevels,
            Set<String> allowedToolNames,
            Set<String> disallowedToolNames
    ) {
        public ToolProfile(
                String name,
                Set<BaseTool.Category> categories,
                Set<BaseTool.Visibility> visibilities,
                Set<BaseTool.RiskLevel> riskLevels
        ) {
            this(name, categories, visibilities, riskLevels, Set.of(), Set.of());
        }

        /**
         * 返回包含全部已注册工具的配置集。
         */
        public static ToolProfile all() {
            return new ToolProfile("all", EnumSet.allOf(BaseTool.Category.class),
                    EnumSet.of(BaseTool.Visibility.ALL), EnumSet.allOf(BaseTool.RiskLevel.class));
        }

        /**
         * 返回只允许读取和检索工具的配置集。
         */
        public static ToolProfile readOnly() {
            return named("read_only", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回计划类子 Agent 可用的只读工具配置集。
         */
        public static ToolProfile plan() {
            return named("plan", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回代码探索子 Agent 可用的工具配置集。
         */
        public static ToolProfile explore() {
            return named("explore", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回验证类子 Agent 可用的工具配置集。
         */
        public static ToolProfile verify() {
            return named("verify", "Read", "Glob", "Grep", "bash");
        }

        /**
         * 返回长期记忆任务专用工具配置集。
         */
        public static ToolProfile memory() {
            return named("memory", "Memory");
        }

        /**
         * 返回通用子 Agent 工具配置集。
         */
        public static ToolProfile general() {
            return new ToolProfile("general", EnumSet.allOf(BaseTool.Category.class),
                    EnumSet.of(BaseTool.Visibility.ALL), EnumSet.allOf(BaseTool.RiskLevel.class));
        }

        /**
         * 按稳定名称和工具名集合创建配置集。
         */
        private static ToolProfile named(String name, String... toolNames) {
            return new ToolProfile(name, EnumSet.allOf(BaseTool.Category.class),
                    EnumSet.of(BaseTool.Visibility.ALL), EnumSet.allOf(BaseTool.RiskLevel.class),
                    Set.of(toolNames), Set.of());
        }
    }
}
