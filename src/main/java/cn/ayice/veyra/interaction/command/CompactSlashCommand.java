package cn.ayice.veyra.interaction.command;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 当前会话的手动上下文压缩命令，只调用 AgentLoop 提供的会话级入口。
 */
public final class CompactSlashCommand implements SlashCommand {

    private final Supplier<String> compactOperation;
    private final Supplier<String> statusOperation;

    public CompactSlashCommand(Supplier<String> compactOperation, Supplier<String> statusOperation) {
        this.compactOperation = Objects.requireNonNull(compactOperation, "compactOperation");
        this.statusOperation = Objects.requireNonNull(statusOperation, "statusOperation");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SlashCommandOption> options() {
        return List.of(
                new SlashCommandOption("compact.run", "压缩上下文", "立即压缩当前 Agent 上下文", "/compact"),
                new SlashCommandOption("compact.status", "上下文状态", "查看容量和当前压缩检查点", "/compact status")
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supports(String input) {
        String command = Objects.requireNonNull(input, "input").trim();
        return command.equals("/compact") || command.equals("/compact status");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SlashCommandResult execute(String input) {
        String command = Objects.requireNonNull(input, "input").trim();
        String content = command.endsWith(" status") ? statusOperation.get() : compactOperation.get();
        return SlashCommandResult.completed("compact_command", content);
    }
}
