package cn.ayice.veyra.context.prompt;

import java.util.stream.Collectors;

/**
 * 系统提示词中的工具使用规则片段。它补充工具 schema 无法表达的行为约束。
 */
public class ToolsSection extends SystemPromptSection {

    public ToolsSection() {
        super("tools", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        String tools = ctx.toolSpecifications().stream()
                .map(spec -> {
                    String description = ctx.toolDescriptions().get(spec.name());
                    return description == null
                            ? "- %s".formatted(spec.name())
                            : "- %s: %s".formatted(spec.name(), description);
                })
                .collect(Collectors.joining("\n"));
        String availableTools = tools.isEmpty()
                ? "可用工具:\n\n"
                : "可用工具:\n\n%s\n".formatted(tools);
        return """
                %s
                工具使用指南:
                - 优先使用 Read / Glob / Grep 了解代码，再使用 Edit / Write 修改
                - 相互独立的工具调用尽量在同一轮中并行发起
                - 小改动优先使用 Edit 而非用 Write 重写整个文件
                - Bash 可执行编译、运行、git 只读检查等命令
                - Agent 用于处理独立的探索、计划、验证或局部实现任务\
                """.formatted(availableTools);
    }
}
