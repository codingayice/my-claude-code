package cn.ayice.veyra.conversation.transcript;

import com.fasterxml.jackson.annotation.JsonIgnore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.time.Instant;
import java.util.UUID;

/**
 * transcript JSONL 的单行模型。它只保存恢复主对话链必须的信息，避免把 LangChain4j 内部对象直接暴露成磁盘格式。
 */
public record TranscriptEntry(
        String id,
        String sessionId,
        String role,
        String content,
        String toolUseId,
        String toolName,
        String timestamp
) {

    public TranscriptEntry {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        if (timestamp == null || timestamp.isBlank()) {
            timestamp = Instant.now().toString();
        }
    }

    /**
     * 创建带当前时间戳的用户 transcript 条目。
     */
    public static TranscriptEntry user(String sessionId, String content) {
        return new TranscriptEntry(null, sessionId, "user", content, null, null, null);
    }

    /**
     * 创建带当前时间戳的助手 transcript 条目。
     */
    public static TranscriptEntry assistant(String sessionId, String content) {
        return new TranscriptEntry(null, sessionId, "assistant", content, null, null, null);
    }

    /**
     * 创建与指定工具调用关联的持久化结果条目。
     */
    public static TranscriptEntry toolResult(String sessionId, String toolUseId, String toolName, String content) {
        return new TranscriptEntry(null, sessionId, "tool_result", content, toolUseId, toolName, null);
    }

    /**
     * 根据输入创建对应对象。
     */
    public static TranscriptEntry fromChatMessage(String sessionId, ChatMessage message) {
        if (message instanceof UserMessage userMessage) {
            return user(sessionId, userText(userMessage));
        }
        if (message instanceof AiMessage aiMessage) {
            return assistant(sessionId, aiMessage.text() == null ? "" : aiMessage.text());
        }
        if (message instanceof ToolExecutionResultMessage resultMessage) {
            return toolResult(sessionId, resultMessage.id(), resultMessage.toolName(), resultMessage.text());
        }
        return new TranscriptEntry(null, sessionId, message.type().name().toLowerCase(), message.toString(), null, null, null);
    }

    /**
     * 将持久化时间戳解析为 Instant；格式非法时返回最早时间。
     */
    @JsonIgnore
    public Instant timestampInstant() {
        return Instant.parse(timestamp);
    }

    /**
     * 提取用户消息的纯文本内容，忽略非文本内容块。
     */
    private static String userText(UserMessage message) {
        if (message.hasSingleText()) {
            return message.singleText();
        }
        return message.toString();
    }
}
