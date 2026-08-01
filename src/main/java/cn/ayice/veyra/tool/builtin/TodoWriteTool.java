package cn.ayice.veyra.tool.builtin;

import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolResult;
import cn.ayice.veyra.tool.ValidationResult;
import cn.ayice.veyra.tool.state.TodoManager;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;

/**
 * 替换当前 todo 列表的工具。模型每次传入完整列表，TodoManager 负责保存并通知前端。
 */
public class TodoWriteTool extends BaseTool {

    private final TodoManager todoManager;

    public TodoWriteTool(TodoManager todoManager) {
        this.todoManager = todoManager;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "TodoWrite";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "创建和管理结构化任务列表，追踪当前会话的工作进度。\n\n" +
                "必须使用的场景:\n" +
                "- 复杂多步骤任务（3个以上独立步骤）\n" +
                "- 用户明确列出多项要完成的工作\n" +
                "- 收到新指令时立即拆分任务\n" +
                "- 探索/分析后识别出多个需要修改的地方\n\n" +
                "不应使用的场景:\n" +
                "- 单一简单任务（如\"改个变量名\"）\n" +
                "- 纯问答/解释性问题\n" +
                "- 一条命令就能完成的任务\n\n" +
                "使用规则:\n" +
                "- 任务必须是祈使句（如\"修复登录空指针\"），activeForm 是进行时（如\"正在修复登录空指针\"）\n" +
                "- 同一时间只能有一个 in_progress 状态的任务\n" +
                "- 完成一项立即标记为 completed，不要攒到一起更新\n" +
                "- 所有任务完成后列表会自动清空\n" +
                "- 不确定时，请使用此工具。主动追踪进度是专业的表现。";
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
        // shouldDefer: 免确认，自动批准
        return PermissionDecision.allow("Todo 管理操作自动批准");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        return ValidationResult.ok();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            String result = todoManager.update(arguments);
            return ToolResult.success(result);
        } catch (Exception e) {
            return ToolResult.error("TodoWrite 执行失败: " + e.getMessage());
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
                        .addProperty("todos", JsonObjectSchema.builder()
                                .description("完整的 todo 项数组。每项包含 content(祈使句任务描述)、status(pending/in_progress/completed)、activeForm(进行时描述，可选)")
                                .addStringProperty("content", "祈使句形式的任务描述，如\"修复登录页面的空指针异常\"")
                                .addStringProperty("status", "任务状态: pending(待处理), in_progress(进行中), completed(已完成)")
                                .addStringProperty("activeForm", "进行时形式的描述，如\"正在修复登录页面空指针异常\"（可选）")
                                .required(List.of("content", "status"))
                                .build())
                        .required(List.of("todos"))
                        .build())
                .build();
    }
}
