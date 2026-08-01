package cn.ayice.veyra.conversation.context.compaction;

import cn.ayice.veyra.conversation.context.WorkingMessage;

import java.util.List;
import java.util.Optional;

/**
 * 一次成功压缩 pass 产生的不可变工作历史、实际策略和可选 checkpoint 候选。
 */
public record CompactionResult(
        List<WorkingMessage> messages,
        CompactStrategy strategy,
        Optional<CheckpointCandidate> checkpointCandidate
) {
    public CompactionResult {
        messages = List.copyOf(messages);
        checkpointCandidate = checkpointCandidate == null ? Optional.empty() : checkpointCandidate;
        if (strategy != CompactStrategy.LLM_SUMMARY && checkpointCandidate.isPresent()) {
            throw new IllegalArgumentException("only LLM_SUMMARY may carry checkpoint candidate");
        }
    }
}
