package cn.ayice.veyra.session.event;

import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;

import java.util.Map;

/**
 * Publishes runtime events to the event stream owned by the current session.
 */
public class SessionAgentEventSink implements AgentEventSink {

    private final SessionEventStream events;
    private final SessionJournalRecorder journalRecorder;

    public SessionAgentEventSink(SessionEventStream events) {
        this(events, null);
    }

    public SessionAgentEventSink(SessionEventStream events, SessionJournalRecorder journalRecorder) {
        this.events = events;
        this.journalRecorder = journalRecorder;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void emit(String type, Map<String, Object> payload) {
        if (journalRecorder != null) {
            String journalType = switch (type) {
                case "run.completed" -> SessionJournalTypes.RUN_COMPLETED;
                case "run.failed" -> SessionJournalTypes.RUN_FAILED;
                case "run.cancelled" -> SessionJournalTypes.RUN_CANCELLED;
                default -> null;
            };
            if (journalType != null) {
                journalRecorder.finishRun(journalType, payload);
            }
        }
        events.emit(type, payload);
    }
}
