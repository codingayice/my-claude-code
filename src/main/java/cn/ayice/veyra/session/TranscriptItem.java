package cn.ayice.veyra.session;

/**
 * Read-only transcript entry exposed by the runtime host.
 */
public record TranscriptItem(
        String id,
        String sessionId,
        String role,
        String content,
        String toolUseId,
        String toolName,
        String timestamp
) {
}
