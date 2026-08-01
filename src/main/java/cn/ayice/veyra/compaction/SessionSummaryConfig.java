package cn.ayice.veyra.compaction;

/**
 * Session Summary 的生成阈值和请求预算，集中约束后台触发与摘要输出大小。
 */
public record SessionSummaryConfig(
        int initialTokens,
        int updateGrowthTokens,
        int toolCallsBetweenUpdates,
        int toolFreeUpdateGrowthTokens,
        int maxInputTokens,
        int maxSummaryTokens,
        int retrySummaryTokens
) {

    public static final int DEFAULT_INITIAL_TOKENS = 10_000;
    public static final int DEFAULT_UPDATE_GROWTH_TOKENS = 5_000;
    public static final int DEFAULT_TOOL_CALLS_BETWEEN_UPDATES = 3;
    public static final int DEFAULT_TOOL_FREE_UPDATE_GROWTH_TOKENS = 10_000;
    public static final int DEFAULT_MAX_INPUT_TOKENS = 12_000;
    public static final int DEFAULT_MAX_SUMMARY_TOKENS = 3_000;
    public static final int DEFAULT_RETRY_SUMMARY_TOKENS = 1_800;

    public SessionSummaryConfig {
        if (initialTokens <= 0 || updateGrowthTokens <= 0 || toolCallsBetweenUpdates <= 0
                || toolFreeUpdateGrowthTokens <= 0 || maxInputTokens <= 0
                || maxSummaryTokens <= 0 || retrySummaryTokens <= 0) {
            throw new IllegalArgumentException("session summary limits must be positive");
        }
        if (retrySummaryTokens >= maxSummaryTokens) {
            throw new IllegalArgumentException("retrySummaryTokens must be lower than maxSummaryTokens");
        }
    }

    /**
     * 创建与增强设计默认值一致的 Session Summary 配置。
     */
    public static SessionSummaryConfig defaults() {
        return new SessionSummaryConfig(
                DEFAULT_INITIAL_TOKENS,
                DEFAULT_UPDATE_GROWTH_TOKENS,
                DEFAULT_TOOL_CALLS_BETWEEN_UPDATES,
                DEFAULT_TOOL_FREE_UPDATE_GROWTH_TOKENS,
                DEFAULT_MAX_INPUT_TOKENS,
                DEFAULT_MAX_SUMMARY_TOKENS,
                DEFAULT_RETRY_SUMMARY_TOKENS
        );
    }
}
