package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.boot.SessionRuntimeFactory;
import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import cn.ayice.veyra.session.persistence.SessionRecord;
import cn.ayice.veyra.session.persistence.TranscriptEntry;
import cn.ayice.veyra.session.persistence.TranscriptRestorer;
import cn.ayice.veyra.session.persistence.TranscriptStore;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RuntimeSessionRegistryTest {

    @TempDir
    Path tempDir;

    @Test
    void createsSessionsAndListsPersistedTranscriptRecords() throws Exception {
        TestRuntime runtime = runtime(config());
        try (RuntimeSessionRegistry sessions = runtime.sessions()) {
            SessionRuntime session = sessions.createSession();
            runtime.store().append(session.sessionId(), TranscriptEntry.user(session.sessionId(), "第一条消息"));

            List<SessionRecord> records = sessions.listSessions();

            assertEquals(1, records.size());
            assertEquals(session.sessionId(), records.get(0).sessionId());
            assertEquals("第一条消息", records.get(0).title());
        }
    }

    @Test
    void restoresAgentAndChatHistoryFromTranscript() throws Exception {
        AppConfig config = config();
        TestRuntime first = runtime(config);
        String sessionId;
        try (RuntimeSessionRegistry sessions = first.sessions()) {
            SessionRuntime created = sessions.createSession();
            sessionId = created.sessionId();
            first.store().append(sessionId, TranscriptEntry.fromChatMessage(sessionId, UserMessage.from("旧问题")));
            first.store().append(sessionId, TranscriptEntry.fromChatMessage(sessionId, AiMessage.from("旧回答")));
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

    @Test
    void readsPersistedTranscriptEntriesForSessionDetail() throws Exception {
        TestRuntime runtime = runtime(config());
        try (RuntimeSessionRegistry sessions = runtime.sessions()) {
            SessionRuntime session = sessions.createSession();
            runtime.store().append(session.sessionId(), TranscriptEntry.user(session.sessionId(), "继续之前的问题"));
            runtime.store().append(session.sessionId(), TranscriptEntry.assistant(session.sessionId(), "可以，先恢复上下文。"));

            List<TranscriptEntry> entries = sessions.transcriptEntries(session.sessionId());

            assertEquals(2, entries.size());
            assertEquals("user", entries.get(0).role());
            assertEquals("继续之前的问题", entries.get(0).content());
            assertEquals("assistant", entries.get(1).role());
        }
    }

    private TestRuntime runtime(AppConfig config) {
        TranscriptStore store = new TranscriptStore(
                new SessionPathResolver(config.getMemoryDir(), config.getWorkspace())
        );
        SessionRuntimeFactory factory = new SessionRuntimeFactory(
                config, store, Runnable::run, Runnable::run, Runnable::run);
        return new TestRuntime(store, new RuntimeSessionRegistry(store, new TranscriptRestorer(), factory));
    }

    private AppConfig config() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                app:
                  name: Test
                model:
                  apiKey: test
                memory:
                  dir: "%s"
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

    private record TestRuntime(TranscriptStore store, RuntimeSessionRegistry sessions) {
    }
}
