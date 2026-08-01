package cn.ayice.veyra.conversation.memory;

import java.time.Instant;

/**
 * 一条完整的长期记忆，包含稳定标识、召回元数据、正文和审计时间。
 */
public record MemoryEntry(
        String id,
        MemoryScope scope,
        MemoryType type,
        MemoryActivation activation,
        String name,
        String description,
        String content,
        Instant createdAt,
        Instant updatedAt,
        String sourceSessionId
) {
}
