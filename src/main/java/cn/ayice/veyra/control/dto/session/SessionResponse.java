package cn.ayice.veyra.control.dto.session;

/**
 * 会话详情和会话设置的基础响应。
 */
public record SessionResponse(
        String sessionId,
        String workingDir,
        String permissionMode,
        String lastRunStatus
) {
}
