package cn.ayice.veyra.interaction.command;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactSlashCommandTest {

    @Test
    void routesRunAndStatusWithoutExecutingTheOtherOperation() {
        AtomicInteger compactCalls = new AtomicInteger();
        AtomicInteger statusCalls = new AtomicInteger();
        CompactSlashCommand command = new CompactSlashCommand(
                () -> "compact " + compactCalls.incrementAndGet(),
                () -> "status " + statusCalls.incrementAndGet()
        );

        SlashCommandResult compact = command.execute("/compact");
        SlashCommandResult status = command.execute("/compact status");

        assertEquals("compact 1", compact.content());
        assertEquals("status 1", status.content());
        assertEquals(1, compactCalls.get());
        assertEquals(1, statusCalls.get());
        assertTrue(command.supports("/compact"));
        assertFalse(command.supports("/compact now"));
    }
}
