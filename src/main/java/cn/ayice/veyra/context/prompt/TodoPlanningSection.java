package cn.ayice.veyra.context.prompt;


/**
 * 系统提示词中的 todo 规划片段。它告诉模型在多步骤任务中何时维护任务列表。
 */
public class TodoPlanningSection extends SystemPromptSection {

    public TodoPlanningSection() {
        super("todo_planning", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        return "任务规划 — TodoWrite 使用规范（极其重要！）:\n" +
                "- 对于涉及 3 个以上独立步骤的复杂任务，必须先用 TodoWrite 创建任务清单，再开始干活\n" +
                "- 收到用户指令后，先分析需要做哪些工作，立即用 TodoWrite 列出所有任务项，然后再逐步执行\n" +
                "- 用户明确给出多项任务（逗号分隔或编号列表）时，必须立即用 TodoWrite 拆分追踪\n" +
                "- 探索代码后发现问题涉及多处修改时，先建 TodoWrite 列出所有修改点\n" +
                "- 每完成一项立即更新状态，不要攒到一起更新；同一时间只有一个 in_progress\n" +
                "- 所有任务完成后 TodoWrite 列表会自动清空\n" +
                "- 不确定要不要用时 — 就用。有任务清单比没有好，主动追踪进度是专业的表现\n" +
                "- 不要先动手再补清单，必须先规划、再执行";
    }
}
