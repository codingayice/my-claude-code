package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolResult;
import cn.ayice.veyra.tooling.ValidationResult;

import cn.ayice.veyra.tooling.task.AgentRunResult;
import cn.ayice.veyra.tooling.task.AgentTaskManager;
import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 启动用户可见子 agent 的工具。它校验子 agent 类型，并把执行交给 AgentTaskManager。
 */
public class AgentTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> USER_AGENT_TYPES = Set.of(
            "Explore", "Plan", "verification", "general-purpose");

    private final AgentTaskManager tasks;

    public AgentTool(AgentTaskManager tasks) {
        this.tasks = tasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "Agent";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "启动一个子 agent 处理聚焦任务。适合独立的代码探索、计划制定、验证检查或局部实现工作。子 agent 拥有隔离的对话状态，并会返回结构化结果。给子 agent 的任务说明应尽量使用中文，并要求最终用中文回答。";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Category category() {
        return Category.ORCHESTRATION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Visibility visibility() {
        return Visibility.MAIN;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.CAUTION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        return PermissionDecision.allow("允许启动子 agent；子 agent 内部的工具调用会单独检查权限。");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        try {
            parseInput(arguments);
            return ValidationResult.ok();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        } catch (Exception e) {
            return ValidationResult.invalid("校验 Agent 输入失败: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            AgentInput input = parseInput(arguments);
            if (input.runInBackground()) {
                String agentId = tasks.submit(input.description(), input.prompt(), input.subagentType(), context);
                ObjectNode output = MAPPER.createObjectNode();
                output.put("status", "async_launched");
                output.put("agentId", agentId);
                output.put("description", input.description());
                output.put("prompt", input.prompt());
                output.put("subagent_type", input.subagentType());
                output.put("message", "子 agent 已在后台启动，完成后会通知主 agent。");
                return ToolResult.success(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output));
            }

            AgentRunResult result = tasks.runSync(input.description(), input.prompt(), input.subagentType(), context);
            return ToolResult.success(formatRunResult(result));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("启动子 agent 失败: " + e.getMessage());
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
                        .addStringProperty("description", "任务的简短描述，建议 3-5 个词")
                        .addStringProperty("prompt", "交给子 agent 执行的完整任务说明；请尽量用中文描述，并要求子 agent 最终用中文回答")
                        .addStringProperty("subagent_type", "可选的子 agent 类型: Explore、Plan、verification 或 general-purpose")
                        .addBooleanProperty("run_in_background", "设为 true 时在后台运行子 agent。默认 false；除非用户明确要求后台执行，否则不要设置为 true。主 agent 在依赖子 agent 结果时应等待结果后再继续。")
                        .required(List.of("description", "prompt"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    /**
     * 解析输入并返回输入。
     */
    private AgentInput parseInput(String arguments) throws IOException {
        JsonNode node = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!node.isObject()) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }
        String description = requiredString(node, "description");
        String prompt = requiredString(node, "prompt");
        String subagentType = optionalString(node, "subagent_type", "general-purpose");
        if (!USER_AGENT_TYPES.contains(subagentType)) {
            throw new IllegalArgumentException("subagent_type 必须是以下之一: Explore, Plan, verification, general-purpose");
        }
        boolean runInBackground = optionalBoolean(node, "run_in_background", false);
        return new AgentInput(description, prompt, subagentType, runInBackground);
    }

    /**
     * 读取必需字符串字段，缺失或空白时返回校验失败。
     */
    private String requiredString(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || !node.get(fieldName).isTextual()) {
            throw new IllegalArgumentException(fieldName + " 是必填字段，且必须是字符串");
        }
        String value = node.get(fieldName).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " 是必填字段");
        }
        return value;
    }

    /**
     * 读取可选字符串字段，缺失时返回空值。
     */
    private String optionalString(JsonNode node, String fieldName, String defaultValue) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return defaultValue;
        }
        if (!node.get(fieldName).isTextual()) {
            throw new IllegalArgumentException(fieldName + " 必须是字符串");
        }
        String value = node.get(fieldName).asText();
        return value.isBlank() ? defaultValue : value;
    }

    /**
     * 读取可选布尔字段，并拒绝非布尔值。
     */
    private boolean optionalBoolean(JsonNode node, String fieldName, boolean defaultValue) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return defaultValue;
        }
        if (!node.get(fieldName).isBoolean()) {
            throw new IllegalArgumentException(fieldName + " 必须是布尔值");
        }
        return node.get(fieldName).asBoolean();
    }

    /**
     * 将输入格式化为运行结果。
     */
    private String formatRunResult(AgentRunResult result) throws IOException {
        ObjectNode output = MAPPER.createObjectNode();
        output.put("status", result.status());
        output.put("agentId", result.agentId());
        output.put("agentType", result.agentType());
        output.put("totalDurationMs", result.totalDurationMs());
        output.put("totalToolUseCount", result.totalToolUseCount());
        output.put("content", result.content());
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(output);
    }

    /**
     * Agent 工具校验后的任务描述、提示词和子 Agent 类型。
     */
    private record AgentInput(
            String description,
            String prompt,
            String subagentType,
            boolean runInBackground
    ) {
    }
}
