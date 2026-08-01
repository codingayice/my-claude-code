package cn.ayice.veyra.conversation.context.compaction;


import cn.ayice.veyra.conversation.context.TokenEstimator;
import cn.ayice.veyra.conversation.context.systemprompt.SystemPromptRegistry;
import cn.ayice.veyra.llm.AIService;
/**
 * 压缩触发来源。AgentTurnPreparer 根据它区分自动维护、手动压缩和响应式恢复。
 */
public enum CompactTrigger {
    AUTO,
    MANUAL,
    REACTIVE
}
