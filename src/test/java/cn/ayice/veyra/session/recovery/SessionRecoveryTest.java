package cn.ayice.veyra.session.recovery;

import cn.ayice.veyra.session.persistence.JournalMessageCodec;
import cn.ayice.veyra.session.persistence.SessionJournalEntry;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void recoversAfterRealChildProcessIsForcefullyTerminated() throws Exception {
        String classPath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path")
        );
        Path javaExecutable = Path.of(
                System.getProperty("java.home"),
                "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java"
        );
        Process child = new ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                classPath,
                JournalCrashHarness.class.getName(),
                tempDir.toString()
        ).redirectErrorStream(true).start();

        assertEquals(23, child.waitFor(), new String(child.getInputStream().readAllBytes()));

        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(tempDir.toString(), "crash-workspace")
        );
        SessionRecovery recovery = new SessionRecovery(store, tempDir, "ask");
        SessionRecovery.RecoveryResult result = recovery.recover("crash-session");
        ToolExecutionResultMessage repaired = (ToolExecutionResultMessage) result.agentHistory().get(2);
        assertTrue(repaired.text().contains("UNKNOWN"));
        assertEquals("interrupted", result.lastRunStatus());

        int stableSize = store.read("crash-session").size();
        recovery.recover("crash-session");
        assertEquals(stableSize, store.read("crash-session").size());
    }

    @Test
    void repairsStartedToolAsUnknownAndIsIdempotent() {
        SessionJournalStore store = store();
        createSessionAndRun(store, "s1", "r1");
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("FileEdit").arguments("{\"path\":\"README.md\"}").build();
        store.append("s1", "r1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                JournalMessageCodec.encode(AiMessage.from(request)), true);
        store.append("s1", "r1", SessionJournalTypes.TOOL_EXECUTION_STARTED,
                Map.of("toolUseId", "call-1", "name", "FileEdit"), true);

        SessionRecovery recovery = recovery(store);
        SessionRecovery.RecoveryResult result = recovery.recover("s1");

        assertEquals("interrupted", result.lastRunStatus());
        assertInstanceOf(ToolExecutionResultMessage.class, result.agentHistory().get(2));
        assertTrue(((ToolExecutionResultMessage) result.agentHistory().get(2)).text().contains("UNKNOWN"));
        assertTrue(result.stableEvents().stream().anyMatch(event ->
                "assistant.message.completed".equals(event.type())
                        && event.payload().containsKey("toolCalls")));
        assertTrue(result.stableEvents().stream().anyMatch(event -> "run.interrupted".equals(event.type())));
        int sizeAfterFirstRecovery = store.read("s1").size();
        recovery.recover("s1");
        assertEquals(sizeAfterFirstRecovery, store.read("s1").size());
    }

    @Test
    void repairsToolThatNeverStartedAsNotExecuted() {
        SessionJournalStore store = store();
        createSessionAndRun(store, "s1", "r1");
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("FileEdit").arguments("{}").build();
        store.append("s1", "r1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                JournalMessageCodec.encode(AiMessage.from(request)), true);

        SessionRecovery.RecoveryResult result = recovery(store).recover("s1");

        ToolExecutionResultMessage toolResult = (ToolExecutionResultMessage) result.agentHistory().get(2);
        assertTrue(toolResult.text().contains("NOT_EXECUTED"));
    }

    @Test
    void restoresSettingsSessionSummaryAndCompleteToolProtocol() {
        SessionJournalStore store = store();
        store.append("s1", null, SessionJournalTypes.SESSION_CREATED, Map.of(
                "workingDir", tempDir.resolve("project").toString(),
                "permissionMode", "auto_approve",
                "runMode", "agent"
        ), true);
        createRunOnly(store, "s1", "r1");
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("call-1").name("FileRead").arguments("{\"path\":\"README.md\"}").build();
        store.append("s1", "r1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                JournalMessageCodec.encode(AiMessage.from("读取文件", List.of(request))), true);
        store.append("s1", "r1", SessionJournalTypes.TOOL_RESULT_RECORDED, Map.of(
                "toolUseId", "call-1", "name", "FileRead", "success", true,
                "outcome", "COMPLETED", "content", "content"
        ), true);
        store.append("s1", "r1", SessionJournalTypes.CONTEXT_SUMMARY_RECORDED, Map.of(
                "summaryText", "summary", "coveredSequence", 2, "summaryVersion", 4
        ), true);
        store.append("s1", "r1", SessionJournalTypes.RUN_COMPLETED, Map.of("reason", "completed"), true);

        SessionRecovery.RecoveryResult result = recovery(store).recover("s1");

        assertEquals(3, result.agentHistory().size());
        assertEquals(1, ((AiMessage) result.agentHistory().get(1)).toolExecutionRequests().size());
        assertEquals(4, result.sessionSummary().orElseThrow().summaryVersion());
        assertEquals("auto_approve", result.settings().permissionMode());
        assertEquals("agent", result.settings().runMode());
        assertEquals("completed", result.lastRunStatus());
    }

    @Test
    void projectsStableUiEventsWithoutPollutingModelContext() {
        SessionJournalStore store = store();
        createSessionAndRun(store, "s1", "r1");
        store.append("s1", "r1", SessionJournalTypes.PERMISSION_REQUESTED, Map.of(
                "approvalId", "approval-1", "toolUseId", "call-1", "tool", "Bash"
        ), true);
        store.append("s1", "r1", SessionJournalTypes.PERMISSION_RESOLVED, Map.of(
                "approvalId", "approval-1", "decision", "allow_once"
        ), true);
        store.append("s1", "r1", SessionJournalTypes.TODO_UPDATED, Map.of(
                "items", List.of(Map.of("content", "验证恢复", "status", "in_progress"))
        ), true);
        store.append("s1", "r1", SessionJournalTypes.TASK_STARTED, Map.of(
                "taskId", "task-1", "description", "检查代码"
        ), true);
        store.append("s1", "r1", SessionJournalTypes.TASK_COMPLETED, Map.of(
                "taskId", "task-1", "content", "完成"
        ), true);
        store.append("s1", "r1", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                JournalMessageCodec.encode(AiMessage.from("最终回答")), true);
        store.append("s1", "r1", SessionJournalTypes.RUN_COMPLETED, Map.of("reason", "completed"), true);

        SessionRecovery.RecoveryResult result = recovery(store).recover("s1");

        assertEquals(2, result.agentHistory().size());
        assertEquals(List.of(
                        "run.started", "user.message", "permission.requested", "permission.resolved",
                        "todo.updated", "task.started", "task.completed",
                        "assistant.message.completed", "run.completed"
                ), result.stableEvents().stream().map(event -> event.type()).toList());
    }

    @Test
    void hidesThinkingForAgentRecoveryButKeepsItForChatRecovery() {
        SessionJournalStore store = store();
        store.append("s1", null, SessionJournalTypes.SESSION_CREATED, Map.of(
                "workingDir", tempDir.toString(), "permissionMode", "ask"
        ), true);

        store.append("s1", "agent-run", SessionJournalTypes.RUN_STARTED, Map.of("mode", "agent"), false);
        store.append("s1", "agent-run", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                JournalMessageCodec.encode(AiMessage.builder()
                        .text("Agent 回答")
                        .thinking("Agent 内部推理")
                        .build()), true);
        store.append("s1", "agent-run", SessionJournalTypes.RUN_COMPLETED,
                Map.of("reason", "completed"), true);

        store.append("s1", "chat-run", SessionJournalTypes.RUN_STARTED, Map.of("mode", "chat"), false);
        store.append("s1", "chat-run", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                JournalMessageCodec.encode(AiMessage.builder()
                        .text("Chat 回答")
                        .thinking("Chat 思考过程")
                        .build()), true);
        store.append("s1", "chat-run", SessionJournalTypes.RUN_COMPLETED,
                Map.of("reason", "completed"), true);

        List<cn.ayice.veyra.session.event.AgentEvent> assistantEvents = recovery(store).recover("s1")
                .stableEvents().stream()
                .filter(event -> "assistant.message.completed".equals(event.type()))
                .toList();

        assertEquals(2, assistantEvents.size());
        assertFalse(assistantEvents.get(0).payload().containsKey("thinking"));
        assertEquals("Chat 思考过程", assistantEvents.get(1).payload().get("thinking"));
    }

    @Test
    void marksPendingApprovalInterruptedDuringRecovery() {
        SessionJournalStore store = store();
        createSessionAndRun(store, "s1", "r1");
        store.append("s1", "r1", SessionJournalTypes.PERMISSION_REQUESTED, Map.of(
                "approvalId", "approval-1", "toolUseId", "call-1", "tool", "Bash"
        ), true);

        SessionRecovery.RecoveryResult result = recovery(store).recover("s1");

        assertTrue(result.stableEvents().stream().anyMatch(event ->
                SessionJournalTypes.PERMISSION_INTERRUPTED.equals(event.type())
                        && "approval-1".equals(event.payload().get("approvalId"))));
    }

    private void createSessionAndRun(SessionJournalStore store, String sessionId, String runId) {
        store.append(sessionId, null, SessionJournalTypes.SESSION_CREATED, Map.of(
                "workingDir", tempDir.toString(), "permissionMode", "ask"
        ), true);
        createRunOnly(store, sessionId, runId);
    }

    private static void createRunOnly(SessionJournalStore store, String sessionId, String runId) {
        store.append(sessionId, runId, SessionJournalTypes.RUN_STARTED, Map.of("mode", "agent"), false);
        store.append(sessionId, runId, SessionJournalTypes.USER_MESSAGE_RECORDED,
                Map.of("text", "修改文件", "visible", true), true);
    }

    private SessionRecovery recovery(SessionJournalStore store) {
        return new SessionRecovery(store, tempDir, "ask");
    }

    private SessionJournalStore store() {
        return new SessionJournalStore(new SessionPathResolver(tempDir.toString(), "D:/workspace"));
    }
}
