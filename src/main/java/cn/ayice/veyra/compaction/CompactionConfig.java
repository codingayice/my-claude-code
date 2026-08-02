package cn.ayice.veyra.compaction;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.ContextService;

/**
 * 上下文压缩模块的只读配置视图，集中保存请求水位和后台摘要策略。
 */
public final class CompactionConfig {

    private static final int OUTPUT_BUDGET_FOR_SUMMARY = 20_000;
    private static final int WARNING_BUFFER = 20_000;
    private static final int MANUAL_COMPACT_BUFFER = 3_000;

    private final int maxContextTokens;
    private final int maxOutputTokens;
    private final boolean autoCompactEnabled;
    private final boolean microCompactEnabled;
    private final Integer autoCompactWindowOverride;
    private final boolean postCompactRestoreEnabled;

    /**
     * 使用已有应用配置字段创建压缩配置，不引入第二份配置来源。
     */
    public CompactionConfig(
            int maxContextTokens,
            int maxOutputTokens,
            boolean autoCompactEnabled,
            boolean microCompactEnabled,
            Integer autoCompactWindowOverride,
            boolean postCompactRestoreEnabled
    ) {
        this.maxContextTokens = maxContextTokens;
        this.maxOutputTokens = maxOutputTokens;
        this.autoCompactEnabled = autoCompactEnabled;
        this.microCompactEnabled = microCompactEnabled;
        this.autoCompactWindowOverride = autoCompactWindowOverride;
        this.postCompactRestoreEnabled = postCompactRestoreEnabled;
    }

    /**
     * 从应用配置投影压缩模块需要的字段。
     */
    public static CompactionConfig from(AppConfig config) {
        return new CompactionConfig(
                config.getMaxContextTokens(),
                config.getMaxTokens(),
                config.isAutoCompactEnabled(),
                config.isMicroCompactEnabled(),
                config.getAutoCompactWindowOverride(),
                config.isPostCompactRestoreEnabled()
        );
    }

    /**
     * 返回模型上下文窗口允许使用的最大 token 数。
     */
    public int getMaxContextTokens() {
        return maxContextTokens;
    }

    /**
     * 返回是否启用达到阈值后的自动上下文压缩。
     */
    public boolean isAutoCompactEnabled() {
        return autoCompactEnabled;
    }

    /**
     * 返回是否启用仅裁剪旧工具结果的微压缩。
     */
    public boolean isMicroCompactEnabled() {
        return microCompactEnabled;
    }

    /**
     * 返回完整压缩后是否恢复必要的系统提示词片段。
     */
    public boolean isPostCompactRestoreEnabled() {
        return postCompactRestoreEnabled;
    }

    /**
     * 根据显式覆盖值和模型上下文上限计算实际自动压缩窗口。
     */
    public int effectiveWindow() {
        if (autoCompactWindowOverride != null && autoCompactWindowOverride > 0) {
            return autoCompactWindowOverride;
        }
        int reserved = Math.min(maxOutputTokens, OUTPUT_BUDGET_FOR_SUMMARY);
        return Math.max(1, maxContextTokens - reserved);
    }

    /**
     * 计算触发自动压缩前必须预留的 token 缓冲量。
     */
    public int autocompactBuffer() {
        int window = effectiveWindow();
        if (window >= 800_000) return 50_000;
        if (window >= 400_000) return 30_000;
        return 13_000;
    }

    /**
     * 返回自动压缩触发阈值。
     */
    public int threshold() {
        return Math.max(1, effectiveWindow() - autocompactBuffer());
    }

    /**
     * 返回前端容量警告阈值。
     */
    public int warningThreshold() {
        return Math.max(1, threshold() - WARNING_BUFFER);
    }

    /**
     * 返回必须阻止继续模型调用的 token 阈值。
     */
    public int blockingLimit() {
        return Math.max(1, effectiveWindow() - MANUAL_COMPACT_BUFFER);
    }

    /**
     * 将压缩窗口投影为 Context 构建所需的只读预算。
     */
    public ContextService.TokenBudget contextTokenBudget() {
        return new ContextService.TokenBudget(getMaxContextTokens(), effectiveWindow(), threshold());
    }

    /**
     * 检查压缩后的请求是否仍会立即触发压缩。
     */
    public boolean willRetrigger(int postCompactTokens) {
        return postCompactTokens >= threshold();
    }

    /**
     * 估算当前历史的 token 使用量并返回压缩阈值状态。
     */
    public TokenState evaluate(int tokenCount) {
        int compactThreshold = threshold();
        int percentLeft = (int) Math.round(((double) (compactThreshold - tokenCount) / compactThreshold) * 100);
        return new TokenState(
                tokenCount,
                Math.max(0, percentLeft),
                tokenCount >= warningThreshold(),
                tokenCount >= compactThreshold,
                tokenCount >= blockingLimit()
        );
    }

    /**
     * 返回后台 Session Summary 的固定默认策略。
     */
    public SummaryPolicy summaryPolicy() {
        return SummaryPolicy.defaults();
    }

    /**
     * 最近一次上下文容量评估结果。
     */
    public record TokenState(
            int tokenCount,
            int percentLeft,
            boolean aboveWarning,
            boolean aboveThreshold,
            boolean atBlockingLimit
    ) {
    }

    /**
     * 后台 Session Summary 的触发阈值和请求预算。
     */
    public record SummaryPolicy(
            int initialTokens,
            int updateGrowthTokens,
            int toolCallsBetweenUpdates,
            int toolFreeUpdateGrowthTokens,
            int maxInputTokens,
            int maxSummaryTokens,
            int retrySummaryTokens
    ) {
        public SummaryPolicy {
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
         * 创建当前默认的后台摘要策略。
         */
        public static SummaryPolicy defaults() {
            return new SummaryPolicy(10_000, 5_000, 3, 10_000, 12_000, 3_000, 1_800);
        }
    }
}
