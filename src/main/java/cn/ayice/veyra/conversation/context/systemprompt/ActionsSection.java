package cn.ayice.veyra.conversation.context.systemprompt;


/**
 * 系统提示词中的行动规则片段。它约束模型如何推进任务、使用工具以及处理失败。
 */
public class ActionsSection extends SystemPromptSection {

    public ActionsSection() {
        super("actions", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        return "风险操作指南:\n" +
                "- 修改已有文件前先用 Read 了解其当前内容\n" +
                "- 新建文件或完整重写使用 Write，小改动优先使用 Edit\n" +
                "- 删除、覆盖或替换文件内容前应自评估操作是否可逆\n" +
                "- git push、git reset --hard 等不可逆操作需要用户确认\n" +
                "- 执行 bash 命令前先评估其影响范围，不确定时先询问用户\n" +
                "- 批量操作（重命名、删除多个文件）前先向用户展示计划并等待确认";
    }
}
