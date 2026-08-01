package cn.ayice.veyra.session.persistence;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 将磁盘 transcript 还原成可以继续发送给 LLM 的 LangChain4j 消息列表。
 */
public class TranscriptRestorer {

    /**
     * 将持久化 transcript 条目恢复为合法模型消息序列。
     */
    public List<ChatMessage> restore(List<TranscriptEntry> entries) {
        List<ChatMessage> messages = new ArrayList<>();
        for (TranscriptEntry entry : entries) {
            ChatMessage message = toMessage(entry);
            if (message != null) {
                messages.add(message);
            }
        }
        return messages;
    }

    /**
     * 按 transcript 角色恢复 LangChain4j 消息；无法安全恢复的条目返回空结果。
     */
    private ChatMessage toMessage(TranscriptEntry entry) {
        return switch (entry.role()) {
            case "user" -> UserMessage.from(entry.content() == null ? "" : entry.content());
            case "assistant" -> AiMessage.from(entry.content() == null ? "" : entry.content());
            case "tool_result" -> ToolExecutionResultMessage.from(
                    entry.toolUseId() == null ? "" : entry.toolUseId(),
                    entry.toolName() == null ? "" : entry.toolName(),
                    entry.content() == null ? "" : entry.content()
            );
            default -> null;
        };
    }
}
