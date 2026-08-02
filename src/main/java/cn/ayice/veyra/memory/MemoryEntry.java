package cn.ayice.veyra.memory;

import java.time.Instant;

/**
 * 一条完整的长期记忆，包含稳定标识、召回元数据、正文和审计时间。
 */
public record MemoryEntry(
        String id,
        Scope scope,
        Type type,
        Activation activation,
        String name,
        String description,
        String content,
        Instant createdAt,
        Instant updatedAt,
        String sourceSessionId
) {
    /**
     * 仅用于候选筛选的 Frontmatter 元数据。正文必须在候选确定后按 id 加载。
     */
    public record Metadata(
            String id,
            Scope scope,
            Type type,
            Activation activation,
            String name,
            String description,
            Instant updatedAt
    ) {
        /**
         * 从完整 topic 提取不含正文的清单字段。
         */
        public static Metadata from(MemoryEntry entry) {
            return new Metadata(
                    entry.id(), entry.scope(), entry.type(), entry.activation(),
                    entry.name(), entry.description(), entry.updatedAt()
            );
        }
    }

    /**
     * 长期记忆的可见范围。
     */
    public enum Scope {
        USER,
        PROJECT
    }

    /**
     * 长期记忆的语义类型。
     */
    public enum Type {
        PREFERENCE,
        FEEDBACK,
        CONTEXT,
        REFERENCE
    }

    /**
     * 长期记忆进入模型上下文的方式。
     */
    public enum Activation {
        ALWAYS,
        RELEVANT
    }
}
