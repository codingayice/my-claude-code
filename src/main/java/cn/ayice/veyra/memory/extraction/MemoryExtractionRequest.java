package cn.ayice.veyra.memory.extraction;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * 一次不可变的长期记忆提取快照，记录待处理消息范围和来源会话。
 */
public record MemoryExtractionRequest(
        String sessionId,
        int fromMessageIndex,
        List<ChatMessage> messages
) {
    /**
     * 对消息列表做防御性复制，避免后台任务读取到主循环的后续修改。
     */
    public MemoryExtractionRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }
}
