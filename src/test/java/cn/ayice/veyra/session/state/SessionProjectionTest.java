package cn.ayice.veyra.session.state;

import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 结构化 AgentState 的纯投影测试。 */
class SessionProjectionTest {
    @TempDir Path tempDir;

    @Test
    void projectsMessagesToolsTodosAndTerminalPhase() {
        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(tempDir.toString(), tempDir.resolve("workspace").toString()));
        store.append("s1", null, SessionJournalTypes.SESSION_CREATED, Map.of("workingDir", tempDir.toString()), true);
        store.append("s1", "r1", SessionJournalTypes.RUN_STARTED, Map.of("parentRunId", ""), true);
        store.append("s1", "r1", SessionJournalTypes.USER_MESSAGE_RECORDED, Map.of("text", "hello"), true);
        store.append("s1", "r1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED, Map.of(
                "text", "", "toolCalls", List.of(Map.of("id", "t1", "name", "Read", "arguments", "{}"))), true);
        store.append("s1", "r1", SessionJournalTypes.TODO_UPDATED,
                Map.of("items", List.of(Map.of("content", "x", "status", "pending"))), true);
        store.append("s1", "r1", SessionJournalTypes.TOOL_RESULT_RECORDED,
                Map.of("toolUseId", "t1", "name", "Read", "outcome", "COMPLETED", "content", "ok"), true);
        store.append("s1", "r1", SessionJournalTypes.RUN_COMPLETED, Map.of("content", "done"), true);

        AgentState state = store.recoveryAgentState("s1");

        assertEquals(AgentPhase.TERMINAL_COMPLETED, state.run().phase());
        assertEquals(3, state.messages().size());
        assertEquals(ToolOutcome.COMPLETED, state.toolCalls().get("t1").outcome());
        assertEquals(1, state.todos().size());
    }
}
