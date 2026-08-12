package cn.ayice.veyra.session.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionBranchPersistenceTest {

    @TempDir
    Path tempDir;

    @Test
    void projectsRunTreeAndPersistsCheckpointSelection() {
        SessionJournalStore store = store();
        createSession(store, "s1");
        terminalRun(store, "s1", "r1", null);
        terminalRun(store, "s1", "r2", "r1");
        terminalRun(store, "s1", "r3", "r1");

        SessionIndex beforeRestore = store.index("s1");
        assertEquals("r3", beforeRestore.currentRunId());
        assertEquals("r1", beforeRestore.runs().get("r2").parentRunId());
        assertEquals("r1", beforeRestore.runs().get("r3").parentRunId());

        SessionIndex restored = store.restoreCheckpoint(
                "s1", "r1", beforeRestore.appliedRevision()
        );

        assertEquals("r1", restored.currentRunId());
        assertEquals(List.of("r1"), new SessionIndexProjector().pathTo(restored, "r1"));
        assertEquals("r1", store.index("s1").currentRunId());
    }

    @Test
    void rebuildsMissingSnapshotAndRepairsMissingIndex() throws Exception {
        SessionJournalStore store = store();
        createSession(store, "s2");
        terminalRun(store, "s2", "r1", null);

        SessionPathResolver paths = paths();
        Path snapshotPath = paths.runSnapshotPath("s2", "r1");
        assertTrue(Files.exists(snapshotPath));
        Files.writeString(snapshotPath, "broken");

        RunSnapshot rebuilt = store.snapshot("s2", "r1");
        assertEquals("r1", rebuilt.runId());
        assertTrue(new JsonRunSnapshotStore(paths).read("s2", "r1").isPresent());

        Files.delete(paths.sessionIndexPath("s2"));
        SessionIndex index = store.index("s2");
        assertEquals("r1", index.currentRunId());
        assertTrue(Files.exists(paths.sessionIndexPath("s2")));
    }

    @Test
    void currentPathExcludesRunsFromOtherChildPath() {
        SessionJournalStore store = store();
        createSession(store, "s3");
        terminalRun(store, "s3", "r1", null);
        terminalRun(store, "s3", "r2", "r1");
        terminalRun(store, "s3", "r3", "r1");

        List<String> currentRunIds = store.currentPathEvents("s3").stream()
                .map(SessionJournalEntry::runId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();

        assertEquals(List.of("r1", "r3"), currentRunIds);
        assertFalse(currentRunIds.contains("r2"));
    }

    @Test
    void recoverySnapshotKeepsLatestSessionSettings() {
        SessionJournalStore store = store();
        createSession(store, "s4");
        terminalRun(store, "s4", "r1", null);
        store.append("s4", null, SessionJournalTypes.SESSION_SETTINGS_UPDATED, Map.of(
                "workingDir", tempDir.resolve("new").toString(),
                "permissionMode", "project_auto",
                "runMode", "agent"
        ), true);

        List<SessionJournalEntry> recovery = store.recoveryEventsAt("s4", "r1");

        SessionJournalEntry settings = recovery.stream()
                .filter(entry -> SessionJournalTypes.SESSION_SETTINGS_UPDATED.equals(entry.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("project_auto", settings.payload().get("permissionMode"));
    }

    private void createSession(SessionJournalStore store, String sessionId) {
        store.append(sessionId, null, SessionJournalTypes.SESSION_CREATED, Map.of(
                "workingDir", tempDir.toString(),
                "permissionMode", "ask_every_time",
                "runMode", "chat"
        ), true);
    }

    private static void terminalRun(
            SessionJournalStore store,
            String sessionId,
            String runId,
            String parentRunId
    ) {
        store.append(sessionId, runId, SessionJournalTypes.RUN_STARTED, Map.of(
                "mode", "chat",
                "input", runId,
                "parentRunId", parentRunId == null ? "" : parentRunId
        ), true);
        store.append(sessionId, runId, SessionJournalTypes.USER_MESSAGE_RECORDED,
                Map.of("text", runId, "visible", true), true);
        store.append(sessionId, runId, SessionJournalTypes.RUN_COMPLETED,
                Map.of("reason", "completed"), true);
    }

    private SessionJournalStore store() {
        return new SessionJournalStore(paths());
    }

    private SessionPathResolver paths() {
        return new SessionPathResolver(tempDir.toString(), tempDir.resolve("workspace").toString());
    }
}
