package cn.ayice.veyra.conversation.memory;

import java.util.List;

/**
 * 一次确定性召回结果，记录最终条目、消耗预算和是否发生裁剪。
 */
public record MemoryRecallResult(
        List<RecalledMemory> memories,
        int usedBytes,
        boolean truncated
) {
    /**
     * 对结果列表做防御性复制。
     */
    public MemoryRecallResult {
        memories = memories == null ? List.of() : List.copyOf(memories);
    }

    /**
     * 单条已召回记忆，正文已经过本次上下文预算裁剪。
     */
    public record RecalledMemory(MemoryEntry entry, String content, int score, boolean truncated) {
    }
}
