package cn.ayice.veyra.control.dto.session;

/**
 * 修改会话工作目录和权限模式的请求。
 */
public record UpdateSessionSettingsRequest(String workingDir, String permissionMode, String runMode) {
}
