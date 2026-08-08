package cn.ayice.veyra.session.persistence;

import java.nio.file.Path;
import java.time.Instant;

/**
 * 会话列表使用的轻量元信息，从 Journal 文件本身推导出来，不维护第二份索引。
 */
public record SessionRecord(
        String sessionId,
        String title,
        Instant createdAt,
        Instant updatedAt,
        Path journalPath
) {
}
