package cn.ayice.veyra.session.persistence;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Session append-only Journal 的单行稳定事实。
 */
public record SessionJournalEntry(
        long sequence,
        String sessionId,
        String runId,
        String type,
        long timestampMs,
        Map<String, Object> payload
) {
    public SessionJournalEntry {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId must not be blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("type must not be blank");
        }
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }
}
