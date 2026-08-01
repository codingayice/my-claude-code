package cn.ayice.veyra.control.service;

import cn.ayice.veyra.boot.SessionRuntimeFactory;
import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.host.RuntimeHost;
import cn.ayice.veyra.host.SessionRegistry;
import cn.ayice.veyra.kernel.RunCoordinator;
import cn.ayice.veyra.control.dto.session.SessionListResponse;
import cn.ayice.veyra.control.dto.session.SessionResponse;
import cn.ayice.veyra.control.dto.session.TranscriptResponse;
import cn.ayice.veyra.conversation.transcript.SessionPathResolver;
import cn.ayice.veyra.conversation.transcript.TranscriptEntry;
import cn.ayice.veyra.conversation.transcript.TranscriptRestorer;
import cn.ayice.veyra.conversation.transcript.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionApplicationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void createsSessionsAndReadsPersistedTranscriptThroughDtoBoundary() throws Exception {
        AppConfig config = config();
        TranscriptStore store = new TranscriptStore(
                new SessionPathResolver(config.getMemoryDir(), config.getWorkspace())
        );
        SessionRuntimeFactory factory = new SessionRuntimeFactory(
                config, store, Runnable::run, Runnable::run, Runnable::run);
        SessionRegistry sessions = new SessionRegistry(store, new TranscriptRestorer(), factory);
        RuntimeHost runtimeHost = new RuntimeHost(sessions, new RunCoordinator());
        SessionApplicationService application = new SessionApplicationService(runtimeHost);

        try (sessions) {
            SessionResponse created = application.createSession();
            store.append(created.sessionId(), TranscriptEntry.user(created.sessionId(), "问题"));
            store.append(created.sessionId(), TranscriptEntry.assistant(created.sessionId(), "回答"));

            SessionListResponse listed = application.listSessions();
            TranscriptResponse transcript = application.transcript(created.sessionId());

            assertEquals(1, listed.items().size());
            assertEquals(created.sessionId(), listed.items().get(0).sessionId());
            assertEquals("问题", listed.items().get(0).title());
            assertEquals(2, transcript.items().size());
            assertEquals("user", transcript.items().get(0).role());
            assertEquals("assistant", transcript.items().get(1).role());
        }
    }

    private AppConfig config() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                model:
                  name: fake
                  baseUrl: http://localhost
                  apiKey: test-key
                  maxTokens: 128
                  timeoutSeconds: 1
                context:
                  maxContextTokens: 128000
                memory:
                  dir: %s
                security:
                  workspace: %s
                permission:
                  mode: ask_every_time
                """.formatted(
                tempDir.resolve("memory").toString().replace("\\", "\\\\"),
                tempDir.toString().replace("\\", "\\\\")
        ));
        return new AppConfig(config.toString());
    }
}
