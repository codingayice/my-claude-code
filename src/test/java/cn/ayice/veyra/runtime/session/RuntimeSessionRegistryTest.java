package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.boot.SessionRuntimeFactory;
import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.session.persistence.JournalMessageCodec;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import cn.ayice.veyra.session.persistence.SessionRecord;
import cn.ayice.veyra.session.recovery.SessionRecovery;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeSessionRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void persistsAndListsSessionOnlyAfterFirstRunIsAccepted() throws Exception {
        TestRuntime runtime = runtime(config());
        try (RuntimeSessionRegistry sessions = runtime.sessions()) {
            SessionRuntime session = sessions.createSession();
            assertTrue(sessions.listSessions().isEmpty());

            assertTrue(session.acceptRun("run-1", "第一条消息", "agent"));
            session.failEnqueue();

            List<SessionRecord> records = sessions.listSessions();

            assertEquals(1, records.size());
            assertEquals(session.sessionId(), records.get(0).sessionId());
            assertEquals("第一条消息", records.get(0).title());
            assertEquals(SessionJournalTypes.SESSION_CREATED,
                    runtime.store().read(session.sessionId()).get(0).type());
        }
    }

    @Test
    void keepsPreRunSettingsInMemoryAndPersistsLatestSnapshotWithFirstRun() throws Exception {
        TestRuntime runtime = runtime(config());
        try (RuntimeSessionRegistry sessions = runtime.sessions()) {
            SessionRuntime session = sessions.createSession();
            Path changedWorkingDir = tempDir.resolve("changed-workspace");

            session.updateSettings(changedWorkingDir.toString(), "ask_every_time", "agent");
            assertTrue(runtime.store().read(session.sessionId()).isEmpty());

            assertTrue(session.acceptRun("run-1", "开始任务", "agent"));
            session.failEnqueue();

            var created = runtime.store().read(session.sessionId()).get(0);
            assertEquals(SessionJournalTypes.SESSION_CREATED, created.type());
            assertEquals(changedWorkingDir.toAbsolutePath().normalize().toString(),
                    created.payload().get("workingDir"));
            assertEquals("ask_every_time", created.payload().get("permissionMode"));
            assertEquals("agent", created.payload().get("runMode"));
        }
    }

    @Test
    void restoresAgentAndChatHistoryFromJournal() throws Exception {
        AppConfig config = config();
        TestRuntime first = runtime(config);
        String sessionId;
        try (RuntimeSessionRegistry sessions = first.sessions()) {
            SessionRuntime created = sessions.createSession();
            sessionId = created.sessionId();
            first.store().append(sessionId, "run-1", SessionJournalTypes.USER_MESSAGE_RECORDED,
                    JournalMessageCodec.encode(UserMessage.from("旧问题")), true);
            first.store().append(sessionId, "run-1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                    JournalMessageCodec.encode(AiMessage.from("旧回答")), true);
        }

        TestRuntime restarted = runtime(config);
        try (RuntimeSessionRegistry sessions = restarted.sessions()) {
            SessionRuntime restored = sessions.getOrCreate(sessionId);

            assertNotNull(restored);
            assertEquals(sessionId, restored.sessionId());
            assertEquals(2, restored.agentHistory().size());
            assertEquals(2, restored.chatHistory().size());
        }
    }

    private TestRuntime runtime(AppConfig config) {
        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(config.getMemoryDir(), config.getWorkspace())
        );
        SessionRuntimeFactory factory = new SessionRuntimeFactory(
                config, store, Runnable::run, Runnable::run, Runnable::run);
        SessionRecovery recovery = new SessionRecovery(
                store, Path.of(config.getWorkspace()), config.getPermissionMode());
        return new TestRuntime(store, new RuntimeSessionRegistry(store, recovery, factory));
    }

    private AppConfig config() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                app:
                  name: Test
                model:
                  apiKey: test
                storage:
                  root: "%s"
                security:
                  workspace: "%s"
                permission:
                  mode: auto_approve
                context:
                  maxRounds: 3
                """.formatted(
                tempDir.toString().replace("\\", "\\\\"),
                tempDir.toString().replace("\\", "\\\\")
        ));
        return new AppConfig(config.toString());
    }

    private record TestRuntime(SessionJournalStore store, RuntimeSessionRegistry sessions) {
    }
}
