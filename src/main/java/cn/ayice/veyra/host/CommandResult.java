package cn.ayice.veyra.host;

/**
 * Result of executing a host-level slash command.
 */
public record CommandResult(String reason, String content) {
}
