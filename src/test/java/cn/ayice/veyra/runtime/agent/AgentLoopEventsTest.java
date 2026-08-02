package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentLoopEventsTest {

    @Test
    void toolEventsCarryToolUseId() {
        RecordingSink sink = new RecordingSink();
        AgentLoopEvents events = new AgentLoopEvents(sink);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("toolu-read-1")
                .name("Read")
                .arguments("{\"file_path\":\"a.txt\"}")
                .build();

        events.toolStarted(request);
        events.toolCompleted(request, ToolResult.success("ok"), "ok");

        assertEquals("toolu-read-1", sink.events.get(0).payload.get("toolUseId"));
        assertEquals("toolu-read-1", sink.events.get(1).payload.get("toolUseId"));
    }

    @Test
    void contextWarningCarriesBackendTokenStateAndThresholdsOnly() {
        RecordingSink sink = new RecordingSink();
        AgentLoopEvents events = new AgentLoopEvents(sink);
        CompactionConfig config = new CompactionConfig(128_000, 4_096, true, true, null, true);

        events.contextWarning(config.evaluate(config.threshold()), config, "request");

        Map<String, Object> payload = sink.events.get(0).payload;
        assertEquals("request", payload.get("phase"));
        assertEquals(110_904, payload.get("tokenCount"));
        assertEquals(128_000, payload.get("maxContextTokens"));
        assertEquals(90_904, payload.get("warningThreshold"));
        assertEquals(110_904, payload.get("threshold"));
        assertEquals(120_904, payload.get("blockingLimit"));
        assertFalse(payload.containsKey("displayTokenCount"));
        assertFalse(payload.containsKey("displayWarningThreshold"));
        assertFalse(payload.containsKey("displayThreshold"));
        assertFalse(payload.containsKey("displayBlockingLimit"));
    }

    private record Event(String type, Map<String, Object> payload) {
    }

    private static final class RecordingSink implements AgentEventSink {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void emit(String type, Map<String, Object> payload) {
            events.add(new Event(type, payload));
        }
    }
}
