package cn.ayice.veyra.runtime;

/**
 * Result of executing a host-level slash command.
 */
public record CommandResult(String reason, String content) {
}
