package cn.ayice.veyra.context.prompt;


/**
 * 系统提示词中的沟通风格片段。它要求助手以中文为主、保持简洁，并说明关键工程决策。
 */
public class CommunicationStyleSection extends SystemPromptSection {

    public CommunicationStyleSection() {
        super("communication_style", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        return "沟通风格:\n" +
                "- 简洁直接，不需要开场白（如「好的」「我来帮你」）和结束语（如「还有什么需要帮忙的吗」）\n" +
                "- 不要使用表情符号\n" +
                "- 使用中文回答用户的问题和总结\n" +
                "- 使用流式散文风格，不要使用项目符号列表输出普通文本回复\n" +
                "- 每次任务完成后简要总结做了什么、验证结果和仍需注意的风险";
    }
}
