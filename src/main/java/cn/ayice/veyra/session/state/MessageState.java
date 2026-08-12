package cn.ayice.veyra.session.state;

import java.util.List;

/** 一条与具体 Run 和事件 revision 关联的持久化模型消息。 */
public record MessageState(
        String messageId,
        long sourceRevision,
        String runId,
        MessageRole role,
        String text,
        String thinking,
        boolean visible,
        List<ToolCallState> toolCalls
) {
    public MessageState {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
