package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.boot.SessionRuntimeFactory;
import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.runtime.control.RunControlRequest;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionRuntimeControlTest {
    @TempDir Path tempDir;

    @Test
    void resolvesBatchIdempotentlyAndRejectsRevisionAndCommandReuse() throws Exception {
        AppConfig config = config();
        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(config.getMemoryDir(), config.getWorkspace()));
        SessionRuntimeFactory factory = new SessionRuntimeFactory(
                config, store, Runnable::run, Runnable::run, Runnable::run);
        SessionRuntime runtime = factory.create("s1", List.of());
        runtime.acceptRun("r1", "do work", "agent");
        runtime.bindRun("r1");
        store.append("s1", "r1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED, Map.of(
                "toolCalls", List.of(
                        Map.of("id", "t1", "name", "Bash", "arguments", "{}"),
                        Map.of("id", "t2", "name", "Write", "arguments", "{}"))), true);
        store.append("s1", "r1", SessionJournalTypes.PERMISSION_REQUESTED,
                Map.of("approvalId", "a1", "toolUseId", "t1", "tool", "Bash"), true);
        store.append("s1", "r1", SessionJournalTypes.PERMISSION_REQUESTED,
                Map.of("approvalId", "a2", "toolUseId", "t2", "tool", "Write"), true);
        long revision = store.index("s1").appliedRevision();
        RunControlRequest batch = new RunControlRequest("resume", "approval", Map.of(
                "decisions", List.of(
                        Map.of("approvalId", "a1", "decision", "allow_once"),
                        Map.of("approvalId", "a2", "decision", "deny"))), revision, "cmd-1");

        var accepted = runtime.control("r1", batch);
        assertEquals(revision + 2, accepted.revision());
        assertEquals("accepted", runtime.control("r1", batch).status());
        assertEquals(2, store.read("s1").stream()
                .filter(event -> SessionJournalTypes.PERMISSION_RESOLVED.equals(event.type())).count());

        RunControlRequest reused = new RunControlRequest("resume", "approval",
                Map.of("approvalId", "a1", "decision", "deny"), accepted.revision(), "cmd-1");
        assertEquals("COMMAND_ID_REUSED", assertThrows(IllegalStateException.class,
                () -> runtime.control("r1", reused)).getMessage());
        RunControlRequest stale = new RunControlRequest("resume", "approval",
                Map.of("approvalId", "a1", "decision", "allow_once"), revision, "cmd-2");
        assertEquals("SESSION_REVISION_CONFLICT", assertThrows(IllegalStateException.class,
                () -> runtime.control("r1", stale)).getMessage());
        runtime.close();
    }

    private AppConfig config() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                model:
                  name: fake
                  baseUrl: http://localhost
                  apiKey: test
                  maxTokens: 128
                storage:
                  root: %s
                security:
                  workspace: %s
                permission:
                  mode: ask_every_time
                """.formatted(tempDir.resolve("memory").toString().replace("\\", "\\\\"),
                tempDir.toString().replace("\\", "\\\\")));
        return new AppConfig(config.toString());
    }
}
