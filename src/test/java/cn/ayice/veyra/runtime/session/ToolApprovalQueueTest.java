package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.session.PendingApprovalState;
import cn.ayice.veyra.session.event.AgentEvent;
import cn.ayice.veyra.session.event.AgentEventSubscriber;
import cn.ayice.veyra.session.event.SessionAgentEventSink;
import cn.ayice.veyra.session.event.SessionEventStream;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolApprovalQueueTest {

    @TempDir
    Path tempDir;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void publishesApprovalLifecycleAndReleasesWaitingTool() throws Exception {
        SessionEventStream events = new SessionEventStream("session-1");
        SessionJournalStore journal = new SessionJournalStore(
                new SessionPathResolver(tempDir.toString(), tempDir.toString()));
        SessionJournalRecorder recorder = new SessionJournalRecorder("session-1", journal);
        ToolApprovalQueue approvals = new ToolApprovalQueue(new SessionAgentEventSink(events, recorder));
        List<AgentEvent> received = new CopyOnWriteArrayList<>();
        CountDownLatch requested = new CountDownLatch(1);
        events.addSubscriber(new AgentEventSubscriber() {
            @Override
            public void send(AgentEvent event) throws IOException {
                received.add(event);
                if ("permission.requested".equals(event.type())) {
                    requested.countDown();
                }
            }

            @Override
            public void close() {
            }
        });
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-use-1")
                .name("Bash")
                .arguments("{\"command\":\"pwd\"}")
                .build();

        var choice = executor.submit(() -> approvals.ask(request, "需要确认"));

        assertTrue(requested.await(1, TimeUnit.SECONDS));
        PendingApprovalState pending = approvals.pendingApprovals().stream()
                .map(item -> new PendingApprovalState(
                        item.approvalId(),
                        item.tool(),
                        item.arguments(),
                        item.reason()
                ))
                .findFirst()
                .orElseThrow();
        assertTrue(approvals.resolveApproval(pending.approvalId(), "allow_once"));

        assertEquals(ToolExecutionConfirmation.Choice.ALLOW_ONCE, choice.get(1, TimeUnit.SECONDS));
        assertEquals(List.of("permission.requested", "permission.resolved"),
                received.stream().map(AgentEvent::type).toList());
        assertEquals("tool-use-1", received.get(0).payload().get("toolUseId"));
        assertEquals(List.of(
                        SessionJournalTypes.PERMISSION_REQUESTED,
                        SessionJournalTypes.PERMISSION_RESOLVED
                ), journal.read("session-1").stream().map(entry -> entry.type()).toList());
    }
}
