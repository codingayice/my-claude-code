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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void projectsApprovalSuspensionAndAdvancesOnlyAfterAllDecisions() {
        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(tempDir.toString(), tempDir.resolve("workspace").toString()));
        store.append("s1", null, SessionJournalTypes.SESSION_CREATED, Map.of("workingDir", tempDir.toString()), true);
        store.append("s1", "r1", SessionJournalTypes.RUN_STARTED, Map.of("mode", "agent"), true);
        store.append("s1", "r1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED, Map.of(
                "toolCalls", List.of(
                        Map.of("id", "t1", "name", "Bash", "arguments", "{}"),
                        Map.of("id", "t2", "name", "Write", "arguments", "{}")
                )), true);
        store.append("s1", "r1", SessionJournalTypes.PERMISSION_REQUESTED, Map.of(
                "approvalId", "a1", "toolUseId", "t1", "tool", "Bash", "arguments", "{}", "reason", "r1"), true);
        store.append("s1", "r1", SessionJournalTypes.PERMISSION_REQUESTED, Map.of(
                "approvalId", "a2", "toolUseId", "t2", "tool", "Write", "arguments", "{}", "reason", "r2"), true);

        AgentState waiting = store.recoveryAgentState("s1");
        assertEquals(AgentPhase.WAITING_APPROVAL, waiting.run().phase());
        assertEquals(ToolCallPhase.WAITING_APPROVAL, waiting.toolCalls().get("t1").phase());
        assertTrue(waiting.hasPendingApprovals());
        assertFalse(waiting.canAdvance());

        store.append("s1", "r1", SessionJournalTypes.PERMISSION_RESOLVED,
                Map.of("approvalId", "a1", "toolUseId", "t1", "decision", "allow_once"), true);
        AgentState partiallyResolved = store.recoveryAgentState("s1");
        assertEquals(AgentPhase.WAITING_APPROVAL, partiallyResolved.run().phase());
        assertEquals(ToolCallPhase.AUTHORIZED, partiallyResolved.toolCalls().get("t1").phase());
        assertTrue(partiallyResolved.hasPendingApprovals());

        store.append("s1", "r1", SessionJournalTypes.PERMISSION_RESOLVED,
                Map.of("approvalId", "a2", "toolUseId", "t2", "decision", "deny"), true);
        AgentState resolved = store.recoveryAgentState("s1");
        assertEquals(AgentPhase.EXECUTING_TOOLS, resolved.run().phase());
        assertEquals(ToolCallPhase.REJECTED, resolved.toolCalls().get("t2").phase());
        assertFalse(resolved.hasPendingApprovals());
        assertTrue(resolved.canAdvance());
    }
}
