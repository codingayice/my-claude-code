package cn.ayice.veyra.session.event;

import java.util.Map;

/**
 * Observable event emitted by one active session runtime.
 */
public record AgentEvent(
        long seq,
        String sessionId,
        String runId,
        String type,
        long timestampMs,
        Map<String, Object> payload
) {
    /**
     * 根据输入创建对应对象。
     */
    public static AgentEvent of(long seq, String sessionId, String runId, String type, Map<String, Object> payload) {
        return new AgentEvent(seq, sessionId, runId, type, System.currentTimeMillis(), payload);
    }

    /**
     * 根据输入创建对应对象。
     */
    public static AgentEvent of(long seq, String sessionId, String runId, String type) {
        return of(seq, sessionId, runId, type, Map.of());
    }
}
