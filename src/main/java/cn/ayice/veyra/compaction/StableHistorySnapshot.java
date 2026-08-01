package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.WorkingMessage;

import java.util.List;

/**
 * 工具批次已经完整汇合时创建的不可变工作历史快照，限定后台摘要可覆盖的真实消息上界。
 */
public record StableHistorySnapshot(long endSequence, List<WorkingMessage> messages) {

    public StableHistorySnapshot {
        if (endSequence <= 0) {
            throw new IllegalArgumentException("endSequence must be positive");
        }
        messages = List.copyOf(messages);
        boolean containsEnd = messages.stream()
                .anyMatch(message -> message.sequence().isPresent()
                        && message.sequence().getAsLong() == endSequence);
        if (!containsEnd) {
            throw new IllegalArgumentException("endSequence must identify a message in the snapshot");
        }
        boolean containsFutureMessage = messages.stream()
                .anyMatch(message -> message.sequence().isPresent()
                        && message.sequence().getAsLong() > endSequence);
        if (containsFutureMessage) {
            throw new IllegalArgumentException("snapshot must not contain messages after endSequence");
        }
    }
}
