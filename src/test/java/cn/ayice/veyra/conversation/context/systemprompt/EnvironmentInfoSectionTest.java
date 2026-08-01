package cn.ayice.veyra.conversation.context.systemprompt;

import cn.ayice.veyra.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentInfoSectionTest {

    @TempDir
    Path tempDir;

    @Test
    void usesPermissionWorkingDirAndExplainsRelativePathRule() {
        Path configWorkspace = tempDir.resolve("config-workspace");
        Path currentWorkingDir = tempDir.resolve("current-working-dir");

        String section = new EnvironmentInfoSection().compute(new SystemPromptContext(
                new TestConfig(configWorkspace),
                List.of(),
                Map.of(),
                currentWorkingDir
        ));

        assertTrue(section.contains("工作目录 workingDir: " + currentWorkingDir.toAbsolutePath().normalize()));
        assertTrue(section.contains("工具中的相对路径只基于 workingDir 解析"));
        assertTrue(section.contains("workingDir 之外的目录，必须使用绝对路径"));
        assertFalse(section.contains(configWorkspace.toString()));
        assertFalse(section.contains("allowedDirectories"));
    }

    private static final class TestConfig extends AppConfig {
        private final Path workspace;

        private TestConfig(Path workspace) {
            super("__missing_environment_info_section_test_config__.yaml");
            this.workspace = workspace;
        }

        @Override
        public String getWorkspace() {
            return workspace.toString();
        }
    }
}
