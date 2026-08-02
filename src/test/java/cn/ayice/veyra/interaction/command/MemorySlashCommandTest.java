package cn.ayice.veyra.interaction.command;

import cn.ayice.veyra.memory.MemoryEntry;
import cn.ayice.veyra.memory.MemoryEntry.Activation;
import cn.ayice.veyra.memory.MemoryFileStore;
import cn.ayice.veyra.memory.MemoryPaths;
import cn.ayice.veyra.memory.MemoryEntry.Scope;
import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.memory.MemoryEntry.Type;
import cn.ayice.veyra.memory.MemoryService.Remember;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySlashCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void listShowAndForgetUseStructuredScopeAndId() {
        MemoryService memory = memory();
        memory.remember(new MemoryService.Remember(
                "project-background",
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Type.CONTEXT,
                MemoryEntry.Activation.RELEVANT,
                "项目背景",
                "Veyra 参考 Claude Code 的 Agent 机制",
                "长期记忆只保存无法从代码推导的项目背景。",
                null
        ));
        MemorySlashCommand command = new MemorySlashCommand(memory, null);

        String listed = command.execute("/memory list project").content();
        String shown = command.execute("/memory show project project-background").content();
        String forgotten = command.execute("/memory forget project project-background").content();

        assertTrue(listed.contains("project-background"));
        assertTrue(shown.contains("长期记忆只保存无法从代码推导的项目背景"));
        assertTrue(forgotten.contains("记忆已删除"));
        assertFalse(command.execute("/memory list project").content().contains("project-background"));
    }

    @Test
    void statusAndPathsDoNotExposeSessionSummaryPaths() {
        MemorySlashCommand command = new MemorySlashCommand(memory(), null);

        String status = command.execute("/memory status").content();
        String paths = command.execute("/memory paths").content();

        assertTrue(status.contains("Enabled: true"));
        assertTrue(status.contains("Last extraction result: disabled"));
        assertTrue(paths.contains("用户记忆:"));
        assertTrue(paths.contains("项目记忆:"));
        assertFalse(paths.contains("会话压缩恢复"));
    }

    @Test
    void unknownCommandShowsUsageWithoutWritingMemory() {
        MemoryService memory = memory();
        MemorySlashCommand command = new MemorySlashCommand(memory, null);

        String result = command.execute("/memory add project 名称 | 描述 | 内容").content();

        assertTrue(result.contains("用法:"));
        assertTrue(memory.list(MemoryEntry.Scope.PROJECT).isEmpty());
    }

    private MemoryService memory() {
        MemoryPaths paths = new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString());
        return new MemoryService(new MemoryFileStore(paths, 16 * 1024, 200, 25 * 1024, 200), 4_096, 5, 4_096, 20_480);
    }
}
