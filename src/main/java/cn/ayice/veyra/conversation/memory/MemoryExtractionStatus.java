package cn.ayice.veyra.conversation.memory;

import java.time.Instant;

/**
 * 当前会话后台长期记忆提取的可诊断状态。
 */
public record MemoryExtractionStatus(
        int cursor,
        boolean running,
        boolean pending,
        Instant lastCompletedAt,
        String lastResult,
        String lastErrorCode
) {
}
