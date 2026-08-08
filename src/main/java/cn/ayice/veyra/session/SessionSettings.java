package cn.ayice.veyra.session;

import java.nio.file.Path;

/**
 * 可持久化和恢复的 Session 基本设置快照。
 */
public record SessionSettings(Path workingDir, String permissionMode) {
    public SessionSettings {
        if (workingDir == null) {
            throw new IllegalArgumentException("workingDir must not be null");
        }
        workingDir = workingDir.toAbsolutePath().normalize();
        permissionMode = permissionMode == null || permissionMode.isBlank() ? "ask" : permissionMode;
    }
}
