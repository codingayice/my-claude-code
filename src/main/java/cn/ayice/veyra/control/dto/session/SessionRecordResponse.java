package cn.ayice.veyra.control.dto.session;

/**
 * 会话侧边栏展示使用的持久化会话摘要。
 */
public record SessionRecordResponse(
        String sessionId,
        String title,
        String createdAt,
        String updatedAt,
        String journalPath
) {
}
