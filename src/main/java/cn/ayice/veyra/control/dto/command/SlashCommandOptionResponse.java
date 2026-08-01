package cn.ayice.veyra.control.dto.command;

/**
 * 输入框 slash command 下拉菜单的一项。
 */
public record SlashCommandOptionResponse(
        String id,
        String name,
        String description,
        String command
) {
}
