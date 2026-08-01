package cn.ayice.veyra.context;

import cn.ayice.veyra.compaction.AutoCompactConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.List;
import java.util.Objects;

/**
 * 完整模型请求的输入预算服务。它统一计量消息和工具 Schema，并按固定阈值分类容量状态。
 */
public final class ContextBudgetService {

    private final AutoCompactConfig config;

    public ContextBudgetService(AutoCompactConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    /**
     * 计量实际 ChatRequest 的输入 token，不包含模型输出预留。
     */
    public int measure(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        long total = TokenEstimator.estimate(request.messages());
        List<ToolSpecification> specifications = request.toolSpecifications();
        if (specifications != null) {
            for (ToolSpecification specification : specifications) {
                total += TokenEstimator.estimateText(specification.toString());
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * 根据固定 warning 和 compact 阈值分类一次完整请求。
     */
    public CapacityState classify(int inputTokens) {
        if (inputTokens >= config.threshold()) {
            return CapacityState.COMPACT_REQUIRED;
        }
        if (inputTokens >= config.warningThreshold()) {
            return CapacityState.WARNING;
        }
        return CapacityState.NORMAL;
    }

    /**
     * 返回扣除模型输出预留后的最大输入窗口。
     */
    public int effectiveWindow() {
        return config.effectiveWindow();
    }

    /**
     * 返回进入 WARNING 状态的完整请求 token 阈值。
     */
    public int warningThreshold() {
        return config.warningThreshold();
    }

    /**
     * 返回必须执行上下文压缩的完整请求 token 阈值。
     */
    public int compactThreshold() {
        return config.threshold();
    }

    /**
     * 一次完整输入请求所处的容量区间。
     */
    public enum CapacityState {
        NORMAL,
        WARNING,
        COMPACT_REQUIRED
    }
}
