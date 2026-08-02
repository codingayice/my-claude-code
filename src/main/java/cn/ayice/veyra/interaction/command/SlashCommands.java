package cn.ayice.veyra.interaction.command;

import cn.ayice.veyra.runtime.MemoryExtractionCoordinator.Status;
import cn.ayice.veyra.memory.MemoryService;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * 内置 slash command 注册工厂。后续新增 /compact、/context 等命令时，只在这里注册。
 */
public final class SlashCommands {

    private SlashCommands() {
    }

    /**
     * 创建包含内置记忆命令的斜杠命令分发器。
     */
    public static SlashCommandDispatcher builtIns(
            MemoryService memoryService,
            Supplier<Status> extractionStatus,
            Supplier<String> compactOperation,
            Supplier<String> compactStatus
    ) {
        Objects.requireNonNull(memoryService, "memoryService");
        return SlashCommandDispatcher.builder()
                .register(new MemorySlashCommand(memoryService, extractionStatus))
                .register(new CompactSlashCommand(compactOperation, compactStatus))
                .build();
    }
}
