package cn.ayice.veyra.context.prompt;


/**
 * 系统提示词中的子 agent 使用规则片段。它说明主 agent 何时同步运行、后台运行或不使用子 agent。
 */
public class SubagentSection extends SystemPromptSection {

    public SubagentSection() {
        super("subagent", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        return "子 agent 使用规范:\n" +
                "- Agent 默认同步执行，不要主动设置 run_in_background=true\n" +
                "- 当主流程下一步依赖子 agent 的探索、计划、验证或实现结果时，必须同步等待结果后再继续\n" +
                "- 只有用户明确要求后台执行/并行执行，或任务完全独立且结果暂时不影响当前下一步时，才设置 run_in_background=true\n" +
                "- 多个互不依赖的探索或验证任务可以异步并行启动，但最终总结、修改或决策前必须检查并整合这些子 agent 的结果\n" +
                "- 异步子 agent 启动后会返回 agentId；需要查看结果时使用 check_task，不要假设后台结果已经完成";
    }
}
