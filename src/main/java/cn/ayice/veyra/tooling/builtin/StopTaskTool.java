package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolResult;
import cn.ayice.veyra.tooling.ValidationResult;

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
 * 停止异步任务的工具。它会按 taskId 尝试取消子 agent 任务和后台 shell 任务。
 */
public class StopTaskTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentTaskManager agentTasks;
    private final BackgroundManager backgroundTasks;

    public StopTaskTool(AgentTaskManager agentTasks, BackgroundManager backgroundTasks) {
        this.agentTasks = agentTasks;
        this.backgroundTasks = backgroundTasks;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "stop_task";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "统一停止正在运行的任务。通过 task_id 停止对应的 subagent 或后台任务。";
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
        return RiskLevel.CAUTION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        return PermissionDecision.allow("停止任务只会终止当前任务。");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        try {
            String taskId = parseTaskId(arguments);
            if (taskId == null || taskId.isBlank()) {
                return ValidationResult.invalid("task_id 是必填字段");
            }
            return ValidationResult.ok();
        } catch (Exception e) {
            return ValidationResult.invalid("校验 stop_task 输入失败: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            String taskId = parseTaskId(arguments);
            if (taskId == null || taskId.isBlank()) {
                return ToolResult.error("task_id 是必填字段");
            }
            if (agentTasks != null && agentTasks.hasTask(taskId) && agentTasks.cancel(taskId)) {
                return ToolResult.success("已停止 subagent 任务 " + taskId);
            }
            if (backgroundTasks != null && backgroundTasks.hasTask(taskId) && backgroundTasks.stop(taskId)) {
                return ToolResult.success("已停止后台任务 " + taskId);
            }
            return ToolResult.error("未找到可停止的任务: " + taskId);
        } catch (Exception e) {
            return ToolResult.error("停止任务失败: " + e.getMessage());
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
                        .addStringProperty("task_id", "任务 ID")
                        .required(List.of("task_id"))
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
}
