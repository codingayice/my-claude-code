package cn.ayice.veyra.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCoordinatorTest {

    @Test
    void emitsStartedAndExecutesSelectedMode() {
        RecordingTarget target = new RecordingTarget(false);
        RunCommand command = new RunCommand("run-1", "session-1", "hello", RunMode.CHAT);

        new RunCoordinator().execute(target, command);

        assertEquals("run-1", target.boundRunId);
        assertEquals(List.of("chat:hello"), target.executions);
        assertEquals(List.of("run.started"), target.eventTypes());
        assertEquals("chat", target.events.get(0).payload().get("mode"));
    }

    @Test
    void convertsUnhandledExecutionFailureToRunFailedEvent() {
        RecordingTarget target = new RecordingTarget(true);
        RunCommand command = new RunCommand("run-2", "session-1", "fail", RunMode.AGENT);

        new RunCoordinator().execute(target, command);

        assertEquals(List.of("run.started", "run.failed"), target.eventTypes());
        Event failed = target.events.get(1);
        assertEquals("run-2", failed.payload().get("runId"));
        assertEquals("boom", failed.payload().get("error"));
        assertTrue(failed.payload().get("content").toString().contains("boom"));
    }

    private static final class RecordingTarget implements RunTarget {
        private final boolean fail;
        private final List<String> executions = new ArrayList<>();
        private final List<Event> events = new ArrayList<>();
        private String boundRunId;

        private RecordingTarget(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void bindRun(String runId) {
            this.boundRunId = runId;
        }

        @Override
        public void emit(String type, Map<String, Object> payload) {
            events.add(new Event(type, new LinkedHashMap<>(payload)));
        }

        @Override
        public void executeAgent(String input) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            executions.add("agent:" + input);
        }

        @Override
        public void executeChat(String input) {
            if (fail) {
                throw new IllegalStateException("boom");
            }
            executions.add("chat:" + input);
        }

        private List<String> eventTypes() {
            return events.stream().map(Event::type).toList();
        }
    }

    private record Event(String type, Map<String, Object> payload) {
    }
}
