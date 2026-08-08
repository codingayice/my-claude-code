package cn.ayice.veyra.session;

/**
 * Read-only control-plane view of an active session.
 */
public record SessionState(
        String sessionId,
        String workingDir,
        String permissionMode,
        String runMode,
        String lastRunStatus
) {
    public SessionState(String sessionId, String workingDir, String permissionMode) {
        this(sessionId, workingDir, permissionMode, "chat", "idle");
    }
}
