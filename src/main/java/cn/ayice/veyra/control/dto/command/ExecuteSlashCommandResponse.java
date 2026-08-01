package cn.ayice.veyra.control.dto.command;

/**
 * slash command 的执行结果。
 */
public record ExecuteSlashCommandResponse(String reason, String content) {
}
