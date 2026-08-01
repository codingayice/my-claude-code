package cn.ayice.veyra.conversation.memory;

/**
 * 创建或更新长期记忆的结构化命令。
 */
public record RememberMemoryCommand(
        String id,
        MemoryScope scope,
        MemoryType type,
        MemoryActivation activation,
        String name,
        String description,
        String content,
        String sourceSessionId
) {
}
