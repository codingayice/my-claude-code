package cn.ayice.veyra.interaction.command;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlashCommandDispatcherTest {

    @Test
    void nonSlashInputIsIgnoredByDispatcher() {
        SlashCommandDispatcher dispatcher = SlashCommandDispatcher.builder()
                .register(new EchoSlashCommand())
                .build();

        Optional<SlashCommandResult> result = dispatcher.dispatch("普通用户输入");

        assertTrue(result.isEmpty());
    }

    @Test
    void emptyRegistryIsInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> SlashCommandDispatcher.builder().build());
    }

    @Test
    void unknownSlashCommandIsNotExecutable() {
        SlashCommandDispatcher dispatcher = SlashCommandDispatcher.builder()
                .register(new EchoSlashCommand())
                .build();

        Optional<SlashCommandResult> result = dispatcher.dispatch("/missing");

        assertTrue(result.isEmpty());
    }

    @Test
    void suggestsCommandsBySlashQuery() {
        SlashCommandDispatcher dispatcher = SlashCommandDispatcher.builder()
                .register(new EchoSlashCommand())
                .build();

        List<SlashCommandOption> result = dispatcher.suggest("/ec");

        assertEquals(1, result.size());
        assertEquals("/echo", result.get(0).command());
    }

    private static final class EchoSlashCommand implements SlashCommand {

        @Override
        public List<SlashCommandOption> options() {
            return List.of(new SlashCommandOption("echo", "Echo", "测试命令", "/echo"));
        }

        @Override
        public boolean supports(String input) {
            return "/echo".equals(input);
        }

        @Override
        public SlashCommandResult execute(String input) {
            return SlashCommandResult.completed("echo", "ok");
        }
    }
}
