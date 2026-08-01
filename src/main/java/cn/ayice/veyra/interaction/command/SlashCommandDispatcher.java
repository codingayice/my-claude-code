package cn.ayice.veyra.interaction.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * slash command 的统一入口。主循环只依赖这个分发器，具体命令通过注册表扩展。
 */
public class SlashCommandDispatcher {

    private final List<SlashCommand> commands;

    public SlashCommandDispatcher(List<SlashCommand> commands) {
        Objects.requireNonNull(commands, "commands");
        if (commands.isEmpty()) {
            throw new IllegalArgumentException("slash command registry must contain at least one command");
        }
        this.commands = List.copyOf(commands);
    }

    /**
     * 创建用于逐步填写字段的空构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 查找并执行匹配的命令；无法识别时返回空结果。
     */
    public Optional<SlashCommandResult> dispatch(String input) {
        String commandText = Objects.requireNonNull(input, "input").trim();
        for (SlashCommand command : commands) {
            if (command.supports(commandText)) {
                return Optional.of(command.execute(commandText));
            }
        }
        return Optional.empty();
    }

    /**
     * 按查询文本返回排序后的命令建议。
     */
    public List<SlashCommandOption> suggest(String query) {
        String normalizedQuery = normalizeQuery(query);
        List<SlashCommandOption> result = new ArrayList<>();
        for (SlashCommand command : commands) {
            for (SlashCommandOption option : command.options()) {
                if (matches(option, normalizedQuery)) {
                    result.add(option);
                }
            }
        }
        return result;
    }

    /**
     * 判断斜杠命令的名称或描述是否匹配规范化查询文本。
     */
    private static boolean matches(SlashCommandOption option, String normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            return true;
        }
        return contains(option.command(), normalizedQuery)
                || contains(option.name(), normalizedQuery)
                || contains(option.description(), normalizedQuery);
    }

    /**
     * 以忽略大小写的方式判断候选文本是否包含查询文本。
     */
    private static boolean contains(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    /**
     * 去除查询首尾空白并转为小写，供命令匹配使用。
     */
    private static String normalizeQuery(String query) {
        String text = query == null ? "" : query.trim();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * Builder 按步骤构建目标对象。
     */
    public static final class Builder {
        private final List<SlashCommand> commands = new ArrayList<>();

        /**
         * 注册组件并保持后续构建顺序稳定。
         */
        public Builder register(SlashCommand command) {
            commands.add(Objects.requireNonNull(command, "command"));
            return this;
        }

        /**
         * 根据当前输入构建目标对象。
         */
        public SlashCommandDispatcher build() {
            return new SlashCommandDispatcher(commands);
        }
    }
}
