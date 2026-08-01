package cn.ayice.veyra.conversation.memory;

import java.time.Instant;

/**
 * 用于索引展示和召回初筛的轻量记忆元数据，不携带正文。
 */
public record MemoryIndexEntry(
        String id,
        MemoryScope scope,
        MemoryType type,
        MemoryActivation activation,
        String name,
        String description,
        Instant updatedAt
) {
    /**
     * 从完整记忆构造不含正文的索引条目。
     */
    public static MemoryIndexEntry from(MemoryEntry entry) {
        return new MemoryIndexEntry(
                entry.id(),
                entry.scope(),
                entry.type(),
                entry.activation(),
                entry.name(),
                entry.description(),
                entry.updatedAt()
        );
    }
}
