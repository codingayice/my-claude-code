package cn.ayice.veyra.subagent;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentTaskManagerTest {

    @Test
    void generatedSubagentNamesDoNotRepeatAfterNamePoolIsExhausted() {
        List<String> startedNames = new ArrayList<>();
        AgentTaskManager manager = new AgentTaskManager(null, Runnable::run);

        for (int i = 0; i < 16; i++) {
            startedNames.add(manager.nextSubagentName());
        }

        assertEquals(16, startedNames.size());
        assertEquals(startedNames.size(), new HashSet<>(startedNames).size());
    }

    @Test
    void oldSubagentEntryPointsAreRemoved() {
        assertFalse(Files.exists(Path.of("src/main/java/cn/ayice/veyra/agent/SubagentRunner.java")));
        assertFalse(Files.exists(Path.of("src/main/java/cn/ayice/veyra/agent/SubagentManager.java")));
        assertFalse(Files.exists(Path.of("src/main/java/cn/ayice/veyra/agent/AgentTaskNotification.java")));
    }
}
