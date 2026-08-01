package cn.ayice.veyra.session.event;

/**
 * agent 事件输出抽象。核心运行时代码只依赖该接口，不直接依赖 HTTP 或 SSE。
 */
public interface AgentEventSink {

    AgentEventSink NOOP = (type, payload) -> {
    };

    /**
     * 处理并传播 {@code emit} 对应的事件。
     */
    void emit(String type, java.util.Map<String, Object> payload);

    /**
     * 处理并传播 {@code emit} 对应的事件。
     */
    default void emit(String type) {
        emit(type, java.util.Map.of());
    }

    /**
     * 处理并传播 {@code emit} 对应的事件。
     */
    default void emit(String type, Object payload) {
        emit(type, java.util.Map.of("value", payload));
    }
}
