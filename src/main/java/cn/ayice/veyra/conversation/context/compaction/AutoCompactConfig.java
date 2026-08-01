package cn.ayice.veyra.conversation.context.compaction;


import cn.ayice.veyra.conversation.context.TokenEstimator;
import cn.ayice.veyra.conversation.context.systemprompt.SystemPromptRegistry;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.config.AppConfig;

/**
 * 自动上下文压缩配置。它从 AppConfig 读取 token 阈值、恢复行为和会话摘要相关开关。
 */
public class AutoCompactConfig {

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
     * 读取配置源并初始化 AutoCompactConfig。
     */
    public AutoCompactConfig(
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
     * 根据输入创建对应对象。
     */
    public static AutoCompactConfig from(AppConfig config) {
        return new AutoCompactConfig(
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
     * @return
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
     * 自动压缩触发阈值，当 token 数量超过这个阈值时，LLM 会尝试自动压缩上下文
     * @return
     */
    public int threshold() {
        return Math.max(1, effectiveWindow() - autocompactBuffer());
    }

    /**
     * 提前警告阈值，用于前端显示警告
     * @return
     */
    public int warningThreshold() {
        return Math.max(1, threshold() - WARNING_BUFFER);
    }

    /**
     * 计算阻塞限制的 token 阈值，超过这个阈值LLM立即停止，提示用户尝试手动压缩来释放空间
     * @return
     */
    public int blockingLimit() {
        return Math.max(1, effectiveWindow() - MANUAL_COMPACT_BUFFER);
    }

    /**
     * 检查在压缩后是否仍然超过阈值
     * @param postCompactTokens
     * @return
     */
    public boolean willRetrigger(int postCompactTokens) {
        return postCompactTokens >= threshold();
    }

    /**
     * 返回最近一次上下文容量评估结果。
     */
    public record TokenState(
            int tokenCount,
            int percentLeft,
            boolean aboveWarning, // 是否达到警告水位
            boolean aboveThreshold, // 是否达到压缩阈值
            boolean atBlockingLimit // 是否达到阻塞限制
    ) {}

    /**
     * 估算当前历史的 token 使用量并返回压缩阈值状态。
     * @param tokenCount
     * @return
     */
    public TokenState evaluate(int tokenCount) {
        int t = threshold();
        int w = warningThreshold();
        int b = blockingLimit();
        int pct = (int) Math.round(((double) (t - tokenCount) / t) * 100);
        if (pct < 0) pct = 0;
        return new TokenState(
                tokenCount,
                pct,
                tokenCount >= w,
                tokenCount >= t,
                tokenCount >= b
        );
    }
}
