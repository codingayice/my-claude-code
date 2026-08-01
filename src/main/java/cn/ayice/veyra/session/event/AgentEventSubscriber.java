package cn.ayice.veyra.session.event;

import java.io.IOException;

/**
 * Subscriber attached to the event stream of one active session.
 */
public interface AgentEventSubscriber {

    /**
     * 处理并传播 {@code send} 对应的事件。
     */
    void send(AgentEvent event) throws IOException;

    /**
     * 释放当前对象持有的运行资源。
     */
    void close();
}
