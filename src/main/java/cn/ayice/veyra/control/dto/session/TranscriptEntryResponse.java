package cn.ayice.veyra.control.dto.session;

/**
 * 前端恢复历史会话气泡使用的 transcript 条目。
 */
public record TranscriptEntryResponse(
        String id,
        String sessionId,
        String role,
        String content,
        String toolUseId,
        String toolName,
        String timestamp
) {
}
