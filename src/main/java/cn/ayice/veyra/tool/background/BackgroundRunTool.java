package cn.ayice.veyra.tool.background;

import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolResult;

import cn.ayice.veyra.tool.background.BackgroundManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.util.List;

/**
 * 后台启动 shell 命令的工具。它把进程注册到 BackgroundManager，并立即返回 taskId。
 */
public class BackgroundRunTool extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final BackgroundManager bg;

    public BackgroundRunTool(BackgroundManager bg) {
        this.bg = bg;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "background_run";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "在后台执行一个任务。此工具会立即返回任务 ID，实际执行在后台继续进行；结果不会直接反馈给 agent，可通过 check_task 查询。适合耗时较长且不需要即时结果的任务。";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Category category() {
        return Category.BACKGROUND;
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
    public ToolResult execute(String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            String command = args.path("command").asText();
            int timeout = args.path("timeout").asInt(120);
            if (command.isEmpty()) {
                return ToolResult.error("参数中缺少 command 字段");
            }
            return ToolResult.success(bg.run(command, timeout));
        } catch (Exception e) {
            return ToolResult.error(e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(name()).description(description())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("command", "要执行的 shell 命令")
                        .addIntegerProperty("timeout", "超时秒数，默认 120")
                        .required(List.of("command"))
                        .build())
                .build();
    }
}
