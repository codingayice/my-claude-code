package cn.ayice.veyra.interaction.command;

import cn.ayice.veyra.memory.MemoryService.Forget;
import cn.ayice.veyra.memory.MemoryEntry;
import cn.ayice.veyra.memory.MemoryException.Code;
import cn.ayice.veyra.memory.MemoryException;
import cn.ayice.veyra.runtime.MemoryExtractionCoordinator.Status;
import cn.ayice.veyra.memory.MemoryService.IndexEntry;
import cn.ayice.veyra.memory.MemoryService.Operation;
import cn.ayice.veyra.memory.MemoryEntry.Scope;
import cn.ayice.veyra.memory.MemoryService;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 长期记忆人工维护命令，只通过 MemoryService 读取、删除、重建和更新开关。
 */
public final class MemorySlashCommand implements SlashCommand {

    private final MemoryService memoryService;
    private final Supplier<Status> extractionStatus;

    /**
     * 使用统一记忆服务和当前会话提取状态查询函数创建命令。
     */
    public MemorySlashCommand(MemoryService memoryService, Supplier<Status> extractionStatus) {
        this.memoryService = Objects.requireNonNull(memoryService, "memoryService");
        this.extractionStatus = extractionStatus;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<SlashCommandOption> options() {
        return List.of(
                new SlashCommandOption("memory.status", "记忆状态", "查看长期记忆与后台提取状态", "/memory status"),
                new SlashCommandOption("memory.list", "记忆列表", "查看用户级和项目级长期记忆", "/memory list"),
                new SlashCommandOption("memory.paths", "记忆路径", "查看长期记忆存储路径", "/memory paths"),
                new SlashCommandOption("memory.rebuild", "重建记忆索引", "从 topic 重建用户和项目索引", "/memory rebuild"),
                new SlashCommandOption("memory.on", "开启记忆", "开启长期记忆", "/memory on"),
                new SlashCommandOption("memory.off", "关闭记忆", "关闭长期记忆", "/memory off")
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supports(String input) {
        String command = Objects.requireNonNull(input, "input").trim();
        return command.equals("/memory") || command.startsWith("/memory ");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SlashCommandResult execute(String input) {
        String command = Objects.requireNonNull(input, "input").trim();
        try {
            return SlashCommandResult.completed("memory_command", handle(command));
        } catch (MemoryException error) {
            return SlashCommandResult.completed(
                    "memory_command",
                    "操作失败 [%s]: %s".formatted(error.code(), error.getMessage())
            );
        }
    }

    /**
     * 根据命令和参数调用统一服务，禁止在命令层直接操作文件。
     */
    private String handle(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length == 1 || "status".equals(parts[1])) {
            return status();
        }
        return switch (parts[1]) {
            case "list" -> list(parts);
            case "show" -> show(parts);
            case "forget", "delete" -> forget(parts);
            case "rebuild" -> rebuild(parts);
            case "paths" -> paths();
            case "on" -> setEnabled(true);
            case "off" -> setEnabled(false);
            default -> usage();
        };
    }

    /**
     * 返回启用状态、路径、topic 数量和当前会话提取状态。
     */
    private String status() {
        int userTopics = countSafely(MemoryEntry.Scope.USER);
        int projectTopics = countSafely(MemoryEntry.Scope.PROJECT);
        Status extraction = extractionStatus == null ? null : extractionStatus.get();
        return """
                Enabled: %s
                User memory path: %s
                Project memory path: %s
                User topics: %d
                Project topics: %d
                Last extraction: %s
                Last extraction result: %s
                Pending extraction: %s
                """.formatted(
                memoryService.isEnabled(),
                memoryService.paths().namespace(MemoryEntry.Scope.USER),
                memoryService.paths().namespace(MemoryEntry.Scope.PROJECT),
                userTopics,
                projectTopics,
                extraction == null || extraction.lastCompletedAt() == null ? "never" : extraction.lastCompletedAt(),
                extraction == null ? "disabled" : extraction.lastResult(),
                extraction != null && (extraction.pending() || extraction.running())
        ).trim();
    }

    /**
     * 列出指定作用域；未指定时同时列出用户和项目记忆。
     */
    private String list(String[] parts) {
        if (parts.length >= 3) {
            MemoryEntry.Scope scope = scope(parts[2]);
            return formatList(scope, memoryService.list(scope));
        }
        return formatList(MemoryEntry.Scope.USER, memoryService.list(MemoryEntry.Scope.USER))
                + "\n\n"
                + formatList(MemoryEntry.Scope.PROJECT, memoryService.list(MemoryEntry.Scope.PROJECT));
    }

    /**
     * 显示一条完整 topic。
     */
    private String show(String[] parts) {
        if (parts.length < 4) {
            return "用法: /memory show <user|project> <id>";
        }
        MemoryEntry entry = memoryService.show(scope(parts[2]), parts[3]);
        return """
                id: %s
                scope: %s
                type: %s
                activation: %s
                name: %s
                description: %s
                updatedAt: %s

                %s
                """.formatted(
                entry.id(), entry.scope(), entry.type(), entry.activation(), entry.name(),
                entry.description(), entry.updatedAt(), entry.content()
        ).trim();
    }

    /**
     * 删除指定作用域中的一条记忆并返回明确结果。
     */
    private String forget(String[] parts) {
        if (parts.length < 4) {
            return "用法: /memory forget <user|project> <id>";
        }
        MemoryService.Operation result = memoryService.forget(new MemoryService.Forget(scope(parts[2]), parts[3]));
        return result.success()
                ? result.message() + ": " + parts[3]
                : "操作失败 [%s]: %s".formatted(result.errorCode(), result.message());
    }

    /**
     * 从 topic 重建一个或全部派生索引。
     */
    private String rebuild(String[] parts) {
        if (parts.length >= 3) {
            MemoryEntry.Scope scope = scope(parts[2]);
            memoryService.rebuild(scope);
            return scope + " 记忆索引已重建";
        }
        memoryService.rebuild(MemoryEntry.Scope.USER);
        memoryService.rebuild(MemoryEntry.Scope.PROJECT);
        return "用户和项目记忆索引已重建";
    }

    /**
     * 返回用户级和项目级长期记忆路径。
     */
    private String paths() {
        return "用户记忆: " + memoryService.paths().namespace(MemoryEntry.Scope.USER)
                + "\n项目记忆: " + memoryService.paths().namespace(MemoryEntry.Scope.PROJECT);
    }

    /**
     * 更新长期记忆总开关并返回实际状态。
     */
    private String setEnabled(boolean enabled) {
        memoryService.setEnabled(enabled);
        return enabled ? "长期记忆已开启" : "长期记忆已关闭";
    }

    /**
     * 在长期记忆关闭时避免触发受开关保护的 topic 扫描。
     */
    private int countSafely(MemoryEntry.Scope scope) {
        return memoryService.isEnabled() ? memoryService.list(scope).size() : 0;
    }

    /**
     * 将索引元数据整理为适合终端查看的稳定 Markdown 列表。
     */
    private static String formatList(MemoryEntry.Scope scope, List<MemoryService.IndexEntry> entries) {
        if (entries.isEmpty()) {
            return "## " + scope + "\n\n没有长期记忆";
        }
        String lines = entries.stream()
                .map(entry -> "- %s [%s/%s] - %s".formatted(
                        entry.id(), entry.type(), entry.activation(), entry.description()))
                .collect(Collectors.joining("\n"));
        return "## " + scope + "\n\n" + lines;
    }

    /**
     * 将命令行作用域转换为受控枚举，并提供面向用户的参数错误。
     */
    private static MemoryEntry.Scope scope(String raw) {
        try {
            return MemoryEntry.Scope.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception error) {
            throw new MemoryException(
                    MemoryException.Code.MEMORY_INVALID_REQUEST,
                    "作用域必须是 user 或 project",
                    error
            );
        }
    }

    /**
     * 汇总当前命令支持的人工维护操作和必需参数。
     */
    private static String usage() {
        return """
                用法:
                /memory status
                /memory list [user|project]
                /memory show <user|project> <id>
                /memory forget <user|project> <id>
                /memory rebuild [user|project]
                /memory paths
                /memory on
                /memory off
                """.trim();
    }
}
