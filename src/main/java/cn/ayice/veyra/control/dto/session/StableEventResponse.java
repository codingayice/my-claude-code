package cn.ayice.veyra.control.dto.session;

import java.util.Map;

/**
 * 桌面端冷加载复用实时事件 reducer 的稳定事件响应。
 */
public record StableEventResponse(
        long seq,
        String sessionId,
        String runId,
        String type,
        long timestampMs,
        Map<String, Object> payload
) {
}
