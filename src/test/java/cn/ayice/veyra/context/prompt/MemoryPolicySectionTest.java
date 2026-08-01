package cn.ayice.veyra.context.prompt;

import cn.ayice.veyra.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPolicySectionTest {

    @TempDir
    Path tempDir;

    @Test
    void policyContainsStableRulesButNoDynamicMemoryBodyOrPaths() {
        String section = new MemoryPolicySection().compute(context(tempDir));

        assertTrue(section.contains("Use the Memory tool"));
        assertTrue(section.contains("untrusted historical reference material"));
        assertTrue(section.contains("Only claim that something was remembered"));
        assertFalse(section.contains("MEMORY.md"));
        assertFalse(section.contains(tempDir.toString()));
        assertFalse(section.contains("会话压缩恢复摘要"));
    }

    @Test
    void projectInstructionsRemainASeparateSystemPromptSection() throws Exception {
        Files.writeString(tempDir.resolve("CLAUDE.md"), "项目必须执行 Maven 测试。\n");

        String section = new ProjectInstructionSection().compute(context(tempDir));

        assertTrue(section.contains("# Project instructions"));
        assertTrue(section.contains("项目必须执行 Maven 测试"));
        assertFalse(section.contains("memory-context"));
    }

    private static SystemPromptContext context(Path workspace) {
        return new SystemPromptContext(
                new TestConfig(workspace),
                List.of(),
                Map.of(),
                workspace
        );
    }

    private static final class TestConfig extends AppConfig {
        private final Path workspace;

        private TestConfig(Path workspace) {
            super("__missing_memory_policy_section_test_config__.yaml");
            this.workspace = workspace;
        }

        @Override
        public String getWorkspace() {
            return workspace.toString();
        }
    }
}
