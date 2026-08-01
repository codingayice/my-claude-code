package cn.ayice.veyra.memory;

import java.util.Set;

/**
 * 一次相关记忆召回请求，包含真实用户输入、排除集合和剩余字节预算。
 */
public record MemoryRecallQuery(
        String userInput,
        Set<String> excludedIds,
        int maxItems,
        int maxTopicBytes,
        int maxTotalBytes
) {
    /**
     * 对可变集合做防御性复制，确保召回过程不受调用方后续修改影响。
     */
    public MemoryRecallQuery {
        excludedIds = excludedIds == null ? Set.of() : Set.copyOf(excludedIds);
    }
}
