package cn.ayice.veyra.runtime;

/**
 * Immutable command accepted by the runtime host for one user message.
 */
public record RunCommand(
        String runId,
        String sessionId,
        String input,
        RunMode mode
) {
}
