package cn.ayice.veyra.session.event;

import java.util.Map;

/**
 * Observable event emitted by one active session runtime.
 */
public record AgentEvent(
        long seq,
        long revision,
        String sessionId,
        String runId,
        String type,
        long timestampMs,
        Map<String, Object> payload
) {
    /**
     * 根据输入创建对应对象。
     */
    public static AgentEvent of(
            long seq, long revision, String sessionId, String runId, String type, Map<String, Object> payload
    ) {
        return new AgentEvent(seq, revision, sessionId, runId, type, System.currentTimeMillis(), payload);
    }

    /**
     * 根据输入创建对应对象。
     */
    public static AgentEvent of(long seq, String sessionId, String runId, String type) {
        return of(seq, 0L, sessionId, runId, type, Map.of());
    }

    /** 创建不关联 Session Journal 的事件。 */
    public static AgentEvent transientEvent(
            long seq, String sessionId, String runId, String type, Map<String, Object> payload
    ) {
        return of(seq, 0L, sessionId, runId, type, payload);
    }
}
