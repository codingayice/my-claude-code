package cn.ayice.veyra.memory;

import dev.langchain4j.data.message.UserMessage;

import java.util.Set;

/**
 * 本轮动态记忆参考消息及其实际注入的稳定 id 集合。
 */
public record MemoryContext(UserMessage message, Set<String> memoryIds, int usedBytes) {
    /**
     * 对集合做防御性复制。
     */
    public MemoryContext {
        memoryIds = memoryIds == null ? Set.of() : Set.copyOf(memoryIds);
    }

    /**
     * 返回没有可注入记忆的空结果。
     */
    public static MemoryContext empty() {
        return new MemoryContext(null, Set.of(), 0);
    }
}
