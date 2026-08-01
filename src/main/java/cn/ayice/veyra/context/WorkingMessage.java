package cn.ayice.veyra.context;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 工作上下文中的消息。原始会话消息携带进程内递增序号，压缩边界和摘要等合成消息不携带序号。
 */
public record WorkingMessage(OptionalLong sequence, ChatMessage message) {

    public WorkingMessage {
        sequence = Objects.requireNonNull(sequence, "sequence");
        message = Objects.requireNonNull(message, "message");
    }

    /**
     * 创建一条带原始消息序号的工作消息。
     */
    public static WorkingMessage original(long sequence, ChatMessage message) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        return new WorkingMessage(OptionalLong.of(sequence), message);
    }

    /**
     * 创建不占用原始消息序号的合成消息。
     */
    public static WorkingMessage synthetic(ChatMessage message) {
        return new WorkingMessage(OptionalLong.empty(), message);
    }

    /**
     * 提取工作消息中的 LangChain4j 消息，供最终请求构建使用。
     */
    public static List<ChatMessage> unwrap(List<WorkingMessage> messages) {
        return messages.stream().map(WorkingMessage::message).toList();
    }
}
