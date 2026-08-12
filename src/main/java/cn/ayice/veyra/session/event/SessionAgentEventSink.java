package cn.ayice.veyra.session.event;

import cn.ayice.veyra.session.event.AgentEventSink;
import java.util.Map;

/**
 * Publishes runtime events to the event stream owned by the current session.
 */
public class SessionAgentEventSink implements AgentEventSink {

    private final SessionEventStream events;
    public SessionAgentEventSink(SessionEventStream events) {
        this.events = events;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void emit(String type, Map<String, Object> payload) {
        events.emit(type, payload);
    }
}
