package cn.ayice.veyra.session.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionJournalStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsDurableFactsWithMonotonicSequence() {
        SessionJournalStore store = store();

        store.append("s1", null, SessionJournalTypes.SESSION_CREATED, Map.of("workingDir", tempDir.toString()), true);
        store.append("s1", "r1", SessionJournalTypes.RUN_STARTED, Map.of("mode", "agent"), true);

        List<SessionJournalEntry> entries = store.read("s1");
        assertEquals(List.of(1L, 2L), entries.stream().map(SessionJournalEntry::sequence).toList());
        assertEquals(SessionJournalTypes.RUN_STARTED, entries.get(1).type());
    }

    @Test
    void truncatesMalformedTailAndContinuesAppending() throws Exception {
        SessionJournalStore store = store();
        store.append("s1", null, SessionJournalTypes.SESSION_CREATED, Map.of(), true);
        Path path = store.journalPath("s1");
        Files.writeString(path, "{broken", java.nio.file.StandardOpenOption.APPEND);

        assertEquals(1, store.read("s1").size());
        store.append("s1", "r1", SessionJournalTypes.RUN_STARTED, Map.of(), true);

        assertEquals(2, store.read("s1").size());
        assertTrue(Files.readString(path).endsWith("\n"));
    }

    @Test
    void rejectsCorruptionInTheMiddle() throws Exception {
        SessionJournalStore store = store();
        Path path = store.journalPath("s1");
        Files.createDirectories(path.getParent());
        Files.writeString(path, "{broken}\n{}\n");

        assertThrows(IllegalStateException.class, () -> store.read("s1"));
    }

    @Test
    void deletesJournalAndClearsItsSessionRecord() {
        SessionJournalStore store = store();
        store.append("s1", null, SessionJournalTypes.SESSION_CREATED, Map.of(), true);

        assertTrue(store.delete("s1"));
        assertFalse(Files.exists(store.journalPath("s1")));
        assertTrue(store.listSessions().isEmpty());
        assertFalse(store.delete("s1"));
    }

    private SessionJournalStore store() {
        return new SessionJournalStore(new SessionPathResolver(tempDir.toString(), "D:/workspace"));
    }
}
