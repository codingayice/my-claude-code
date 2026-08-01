package cn.ayice.veyra.interaction.command;

import java.util.Objects;

/**
 * slash command 的执行结果。reason 用于事件流标记终止原因，content 是展示给用户的文本。
 */
public record SlashCommandResult(String reason, String content) {

    public SlashCommandResult {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) {
            throw new IllegalArgumentException("slash command result reason must not be blank");
        }
        Objects.requireNonNull(content, "content");
    }

    /**
     * 创建已在控制面完成、无需进入 Agent 循环的命令结果。
     */
    public static SlashCommandResult completed(String reason, String content) {
        return new SlashCommandResult(reason, content);
    }
}
