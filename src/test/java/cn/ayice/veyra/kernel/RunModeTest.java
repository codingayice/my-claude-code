package cn.ayice.veyra.kernel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunModeTest {

    @Test
    void defaultsToAgentForBackwardCompatibility() {
        assertEquals(RunMode.AGENT, RunMode.from(null));
        assertEquals(RunMode.AGENT, RunMode.from(""));
        assertEquals(RunMode.AGENT, RunMode.from("unknown"));
    }

    @Test
    void parsesChatAndAgentModesCaseInsensitively() {
        assertEquals(RunMode.CHAT, RunMode.from("chat"));
        assertEquals(RunMode.CHAT, RunMode.from(" CHAT "));
        assertEquals(RunMode.AGENT, RunMode.from("agent"));
        assertEquals(RunMode.AGENT, RunMode.from("Agent"));
    }
}
