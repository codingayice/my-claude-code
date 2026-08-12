package cn.ayice.veyra.session.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/** 由 Session Event Stream 派生、可删除重建的 Run 树物化索引。 */
public record SessionIndex(
        int schemaVersion,
        String sessionId,
        long appliedRevision,
        String currentRunId,
        String activeRunId,
        Map<String, RunIndexEntry> runs
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public SessionIndex {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported SessionIndex schemaVersion: " + schemaVersion);
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (appliedRevision < 0) {
            throw new IllegalArgumentException("appliedRevision must not be negative");
        }
        runs = runs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(runs));
    }

    /** 创建尚无任何事件的空索引。 */
    public static SessionIndex empty(String sessionId) {
        return new SessionIndex(CURRENT_SCHEMA_VERSION, sessionId, 0L, null, null, Map.of());
    }
}
