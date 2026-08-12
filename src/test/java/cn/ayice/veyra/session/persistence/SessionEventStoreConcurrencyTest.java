package cn.ayice.veyra.session.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Event Store expectedRevision 和 eventId 语义测试。 */
class SessionEventStoreConcurrencyTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsStaleRevisionAndReturnsTheOriginalEventForAnIdempotentRetry() {
        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(tempDir.toString(), tempDir.resolve("workspace").toString()));
        SessionJournalEntry first = store.append("s1", null, SessionJournalTypes.SESSION_CREATED,
                Map.of("workingDir", tempDir.toString()), true, 0L, "event-1");

        SessionJournalEntry retry = store.append("s1", null, SessionJournalTypes.SESSION_CREATED,
                Map.of("workingDir", tempDir.toString()), true, 0L, "event-1");

        assertEquals(first, retry);
        assertEquals(1, store.read("s1").size());
        IllegalStateException conflict = assertThrows(IllegalStateException.class, () ->
                store.append("s1", null, SessionJournalTypes.SESSION_SETTINGS_UPDATED,
                        Map.of("workingDir", tempDir.toString()), true, 0L, "event-2"));
        assertEquals("SESSION_REVISION_CONFLICT", conflict.getMessage());
    }

    @Test
    void rejectsReusingAnEventIdForDifferentFacts() {
        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(tempDir.toString(), tempDir.resolve("workspace").toString()));
        store.append("s1", null, SessionJournalTypes.SESSION_CREATED,
                Map.of("workingDir", tempDir.toString()), true, 0L, "event-1");

        IllegalStateException conflict = assertThrows(IllegalStateException.class, () ->
                store.append("s1", null, SessionJournalTypes.SESSION_SETTINGS_UPDATED,
                        Map.of("workingDir", "different"), true, 1L, "event-1"));
        assertEquals("EVENT_ID_CONFLICT", conflict.getMessage());
    }
}
