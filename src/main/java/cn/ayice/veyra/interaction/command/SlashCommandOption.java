package cn.ayice.veyra.interaction.command;

/**
 * 输入框可展示和执行的 slash command 选项。
 */
public record SlashCommandOption(
        String id,
        String name,
        String description,
        String command
) {
}
