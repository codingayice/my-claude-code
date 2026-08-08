package cn.ayice.veyra.session;

import java.nio.file.Path;

/**
 * 可持久化和恢复的 Session 基本设置快照。
 */
public record SessionSettings(Path workingDir, String permissionMode, String runMode) {
    public SessionSettings {
        if (workingDir == null) {
            throw new IllegalArgumentException("workingDir must not be null");
        }
        workingDir = workingDir.toAbsolutePath().normalize();
        permissionMode = permissionMode == null || permissionMode.isBlank() ? "ask" : permissionMode;
        runMode = runMode == null || runMode.isBlank() ? "chat" : runMode.trim().toLowerCase();
        if (!"chat".equals(runMode) && !"agent".equals(runMode)) {
            throw new IllegalArgumentException("runMode must be chat or agent");
        }
    }
}
