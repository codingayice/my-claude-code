package cn.ayice.veyra.context.prompt;


import cn.ayice.veyra.compaction.AutoCompactConfig;

/**
 * 系统提示词中的 token 预算片段。它向模型暴露压缩阈值，并提醒模型控制上下文规模。
 */
public class TokenBudgetSection extends SystemPromptSection {

    private final AutoCompactConfig compactConfig;

    public TokenBudgetSection(AutoCompactConfig compactConfig) {
        super("token_budget", false);
        this.compactConfig = compactConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        int maxCtx = compactConfig.getMaxContextTokens();
        int window = compactConfig.effectiveWindow();
        int threshold = compactConfig.threshold();
        return "上下文预算:\n"
                + "- 模型上下文窗口: " + maxCtx + " tokens\n"
                + "- 有效窗口: " + window + " tokens\n"
                + "- 压缩触发阈值: " + threshold + " tokens\n"
                + "- 超出阈值时自动压缩旧历史，保留最近消息\n"
                + "- 请根据剩余空间控制输出长度";
    }
}
