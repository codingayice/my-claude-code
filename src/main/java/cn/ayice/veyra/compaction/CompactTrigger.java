package cn.ayice.veyra.compaction;


import cn.ayice.veyra.context.TokenEstimator;
import cn.ayice.veyra.context.prompt.SystemPromptRegistry;
import cn.ayice.veyra.llm.AIService;
/**
 * 压缩触发来源。CompactionService 根据它区分自动维护、手动压缩和响应式恢复。
 */
public enum CompactTrigger {
    AUTO,
    MANUAL,
    REACTIVE
}
