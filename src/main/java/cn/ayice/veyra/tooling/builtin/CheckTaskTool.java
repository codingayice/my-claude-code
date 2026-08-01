package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolResult;

import cn.ayice.veyra.tooling.task.BackgroundManager;
import cn.ayice.veyra.tooling.task.AgentTaskManager;
import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionDecision;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;

/**
 * 查询异步任务结果的工具。它可以读取子 agent 任务结果和后台命令输出。
 */
public class CheckTaskTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentTaskManager agentTasks;
    private final BackgroundManager backgroundTasks;

    public CheckTaskTool(AgentTaskManager agentTasks, BackgroundManager backgroundTasks) {
        this.agentTasks = agentTasks;
        this.backgroundTasks = backgroundTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "check_task";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "统一查询任务状态和结果。可传 task_id；可不传列出全部任务。";
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
        return PermissionDecision.allow("查询任务是安全操作。");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            String taskId = parseTaskId(arguments);
            if (taskId == null || taskId.isBlank()) {
                return ToolResult.success(formatAll());
            }
            if (agentTasks != null && agentTasks.hasTask(taskId)) {
                return ToolResult.success(agentTasks.check(taskId));
            }
            if (backgroundTasks != null && backgroundTasks.hasTask(taskId)) {
                return ToolResult.success(backgroundTasks.check(taskId));
            }
            return ToolResult.error("未找到任务: " + taskId);
        } catch (Exception e) {
            return ToolResult.error("查询任务失败: " + e.getMessage());
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
                        .addStringProperty("task_id", "可选，任务 ID")
                        .required(List.of())
                        .build())
                .build();
    }

    /**
     * 读取并校验必填任务标识；缺失或空白时拒绝调用。
     */
    private String parseTaskId(String arguments) throws Exception {
        JsonNode node = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!node.has("task_id") || node.get("task_id").isNull()) {
            return null;
        }
        if (!node.get("task_id").isTextual()) {
            throw new IllegalArgumentException("task_id 必须是字符串");
        }
        return node.get("task_id").asText();
    }

    /**
     * 将输入格式化为全部内容。
     */
    private String formatAll() {
        StringBuilder sb = new StringBuilder();
        if (agentTasks != null) {
            sb.append(agentTasks.check(null)).append("\n");
        }
        if (backgroundTasks != null) {
            sb.append(backgroundTasks.check(null)).append("\n");
        }
        return sb.toString().trim();
    }
}
