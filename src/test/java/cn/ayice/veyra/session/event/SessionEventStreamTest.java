package cn.ayice.veyra.session.event;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionEventStreamTest {

    @Test
    void publishesEventsToGenericSubscribers() {
        SessionEventStream events = new SessionEventStream("session-1");
        List<AgentEvent> received = new ArrayList<>();
        AgentEventSubscriber subscriber = new AgentEventSubscriber() {
            @Override
            public void send(AgentEvent event) throws IOException {
                received.add(event);
            }

            @Override
            public void close() {
            }
        };

        events.addSubscriber(subscriber);
        events.bindRun("run-1");
        events.emit("run.started", Map.of("text", "hello"));
        events.removeSubscriber(subscriber);

        assertEquals(1, received.size());
        assertEquals("session-1", received.get(0).sessionId());
        assertEquals("run-1", received.get(0).runId());
        assertEquals("run.started", received.get(0).type());
        assertEquals("hello", received.get(0).payload().get("text"));
    }
}
