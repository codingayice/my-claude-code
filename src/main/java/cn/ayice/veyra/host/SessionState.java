package cn.ayice.veyra.host;

/**
 * Read-only control-plane view of an active session.
 */
public record SessionState(
        String sessionId,
        String workingDir,
        String permissionMode
) {
}
