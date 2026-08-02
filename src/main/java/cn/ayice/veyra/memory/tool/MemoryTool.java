package cn.ayice.veyra.memory.tool;

import cn.ayice.veyra.memory.MemoryService.Forget;
import cn.ayice.veyra.memory.MemoryEntry.Activation;
import cn.ayice.veyra.memory.MemoryEntry;
import cn.ayice.veyra.memory.MemoryException;
import cn.ayice.veyra.memory.MemoryService.IndexEntry;
import cn.ayice.veyra.memory.MemoryService.Operation;
import cn.ayice.veyra.memory.MemoryEntry.Scope;
import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.memory.MemoryEntry.Type;
import cn.ayice.veyra.memory.MemoryService.Remember;
import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolResult;
import cn.ayice.veyra.tool.ValidationResult;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;
import java.util.Locale;

/**
 * Agent 操作长期记忆的唯一工具入口，只接受结构化字段，不接受任意文件路径。
 */
public final class MemoryTool extends BaseTool {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final MemoryService memoryService;

    /**
     * 使用统一记忆服务创建工具。
     */
    public MemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "Memory";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return """
                管理跨会话长期记忆。用户明确要求记住、忘记、查看或列出记忆时必须使用本工具。
                remember 只保存长期稳定的偏好、行为反馈、无法从代码推导的项目背景和外部参考入口。
                不得保存 transcript、上下文压缩摘要、当前任务进度、Todo、工具输出、文件清单、Git 历史或敏感凭据。
                稳定个人偏好使用 USER；仅当前项目适用的信息使用 PROJECT。
                自动提取默认使用 RELEVANT；ALWAYS 只允许 USER/PREFERENCE。
                只有工具返回 success=true 后，才能向用户声称已经记住或忘记。
                """.trim();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Category category() {
        return Category.UTILITY;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Visibility visibility() {
        return Visibility.ALL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.SAFE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        return PermissionDecision.allow("结构化长期记忆操作由 MemoryService 校验");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(arguments == null ? "{}" : arguments);
            String action = text(root, "action");
            if (!List.of("remember", "forget", "list", "show").contains(action)) {
                return ValidationResult.invalid("action 必须是 remember、forget、list 或 show");
            }
            parseEnum(MemoryEntry.Scope.class, text(root, "scope"), "scope");
            return ValidationResult.ok();
        } catch (Exception error) {
            return ValidationResult.invalid("Memory 参数不合法: " + error.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(arguments == null ? "{}" : arguments);
            String action = text(root, "action");
            MemoryEntry.Scope scope = parseEnum(MemoryEntry.Scope.class, text(root, "scope"), "scope");
            return switch (action) {
                case "remember" -> remember(root, scope);
                case "forget" -> operation(memoryService.forget(new MemoryService.Forget(scope, text(root, "id"))));
                case "list" -> ToolResult.success(formatList(memoryService.list(scope)));
                case "show" -> ToolResult.success(formatEntry(memoryService.show(scope, text(root, "id"))));
                default -> ToolResult.error("不支持的 Memory action: " + action);
            };
        } catch (MemoryException error) {
            return ToolResult.error(error.code() + ": " + error.getMessage());
        } catch (Exception error) {
            return ToolResult.error("Memory 执行失败: " + error.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("action", "操作: remember、forget、list、show")
                        .addStringProperty("scope", "作用域: USER 或 PROJECT")
                        .addStringProperty("id", "稳定记忆标识；remember 可省略，其他按需提供")
                        .addStringProperty("type", "remember 类型: PREFERENCE、FEEDBACK、CONTEXT、REFERENCE")
                        .addStringProperty("activation", "remember 激活方式: ALWAYS 或 RELEVANT")
                        .addStringProperty("name", "remember 显示名称，不超过 80 个字符")
                        .addStringProperty("description", "remember 适用条件摘要，不超过 200 个字符")
                        .addStringProperty("content", "remember 长期记忆正文")
                        .addStringProperty("source_session_id", "可选来源会话标识，仅用于诊断")
                        .required(List.of("action", "scope"))
                        .build())
                .build();
    }

    /**
     * 解析 remember 专用字段并委托统一服务持久化。
     */
    private ToolResult remember(JsonNode root, MemoryEntry.Scope scope) {
        MemoryService.Operation result = memoryService.remember(new MemoryService.Remember(
                optionalText(root, "id"),
                scope,
                parseEnum(MemoryEntry.Type.class, text(root, "type"), "type"),
                parseEnum(MemoryEntry.Activation.class, text(root, "activation"), "activation"),
                text(root, "name"),
                text(root, "description"),
                text(root, "content"),
                optionalText(root, "source_session_id")
        ));
        return operation(result);
    }

    /**
     * 将统一操作结果转换为模型可判断的稳定文本。
     */
    private static ToolResult operation(MemoryService.Operation result) {
        if (!result.success()) {
            String code = result.errorCode() == null ? "MEMORY_OPERATION_FAILED" : result.errorCode().name();
            return ToolResult.error(code + ": " + result.message());
        }
        String id = result.entry() == null ? "" : ", id=" + result.entry().id();
        return ToolResult.success("success=true, message=" + result.message() + id);
    }

    /**
     * 将索引列表格式化为紧凑、可读的工具结果。
     */
    private static String formatList(List<MemoryService.IndexEntry> entries) {
        if (entries.isEmpty()) {
            return "没有长期记忆";
        }
        return entries.stream()
                .map(entry -> "- %s | %s | %s | %s".formatted(
                        entry.id(), entry.type(), entry.activation(), entry.description()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    /**
     * 将单条记忆格式化为人工可检查内容。
     */
    private static String formatEntry(MemoryEntry entry) {
        return """
                id: %s
                scope: %s
                type: %s
                activation: %s
                name: %s
                description: %s
                updatedAt: %s

                %s
                """.formatted(
                entry.id(), entry.scope(), entry.type(), entry.activation(),
                entry.name(), entry.description(), entry.updatedAt(), entry.content()
        ).trim();
    }

    /**
     * 提取工具必填字符串字段，拒绝缺失、null 和空白值。
     */
    private static String text(JsonNode root, String field) {
        String value = optionalText(root, field);
        if (value == null) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        return value;
    }

    /**
     * 提取并规范化工具可选字符串字段。
     */
    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) {
            return null;
        }
        return value.asText().trim();
    }

    /**
     * 将模型参数按大小写无关规则转换为受控枚举，并保留字段名用于诊断。
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            throw new IllegalArgumentException(field + " 不合法", error);
        }
    }
}
