package cn.ayice.veyra.boot;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.conversation.transcript.SessionPathResolver;
import cn.ayice.veyra.conversation.transcript.TranscriptStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SessionRuntimeFactoryMemoryPathTest {

    @TempDir
    Path tempDir;

    @Test
    void factoryDoesNotRequireLegacySessionSummaryStorage() throws Exception {
        Path workspace = tempDir.resolve("workspace").resolve("my-claude-code");
        java.nio.file.Files.createDirectories(workspace);
        Path memoryRoot = tempDir.resolve("mycc-root");
        TestConfig config = new TestConfig(memoryRoot, workspace.resolve(".").toString());
        TranscriptStore store = new TranscriptStore(
                new SessionPathResolver(memoryRoot.toString(), workspace.toString())
        );

        assertDoesNotThrow(() -> new SessionRuntimeFactory(
                config, store, Runnable::run, Runnable::run, Runnable::run));
    }

    private static final class TestConfig extends AppConfig {
        private final Path memoryRoot;
        private final String workspace;

        private TestConfig(Path memoryRoot, String workspace) {
            super("__missing_session_runtime_factory_memory_path_test_config__.yaml");
            this.memoryRoot = memoryRoot;
            this.workspace = workspace;
        }

        @Override
        public String getMemoryDir() {
            return memoryRoot.toString();
        }

        @Override
        public String getWorkspace() {
            return workspace;
        }
    }
}
