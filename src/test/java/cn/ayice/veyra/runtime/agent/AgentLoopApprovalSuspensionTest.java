package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.compaction.SessionSummaryState;
import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.tool.ToolResult;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.tool.state.FileStateCache;
import cn.ayice.veyra.tool.state.TodoManager;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class AgentLoopApprovalSuspensionTest {
    @TempDir Path tempDir;

    @Test
    void returnsSuspendedWithoutExecutingOrRetainingWaitingThread() {
        AtomicInteger executions = new AtomicInteger();
        BaseTool tool = new AskingTool(executions);
        ToolCatalog catalog = ToolCatalog.create(List.of(tool), new FileStateCache());
        CompactionConfig compact = new CompactionConfig(1_000_000, 4_096, true, true, null, true);
        AppConfig config = new AppConfig("__missing_approval_suspension_test__.yaml");
        SessionJournalStore store = new SessionJournalStore(
                new SessionPathResolver(tempDir.resolve("storage").toString(), tempDir.toString()));
        SessionJournalRecorder recorder = new SessionJournalRecorder("s1", store);
        PermissionContext context = PermissionContext.builder().mode(PermissionMode.ASK_EVERY_TIME)
                .workingDir(tempDir).addAllowedDirectory(tempDir).build();
        recorder.acceptRun("r1", "run", "agent", tempDir, "ask_every_time", "agent");
        recorder.bindRun("r1");
        AgentLoop loop = new AgentLoop(new ToolRequestAI(), catalog,
                new ContextService(catalog.specifications(), catalog.descriptions(), config, null,
                        compact.contextTokenBudget()), null, new PermissionContextStore(context),
                new TodoManager(null), compact, 10, null, AgentEventSink.NOOP,
                new SessionSummaryState(), null, new FileStateCache(), 10_000, null,
                List.of(), recorder, Runnable::run);

        AgentStepResult result = assertTimeoutPreemptively(Duration.ofSeconds(1), () -> loop.processStep("run"));

        assertEquals("suspended", result.status());
        assertEquals(0, executions.get());
        assertEquals(1, store.read("s1").stream()
                .filter(event -> SessionJournalTypes.PERMISSION_REQUESTED.equals(event.type())).count());
        loop.shutdown();
    }

    private static final class ToolRequestAI extends AIService {
        private ToolRequestAI() { super(new AppConfig("__missing_approval_suspension_test__.yaml")); }

        @Override
        public CompletableFuture<AiMessage> streamingChat(List<ChatMessage> messages,
                                                           List<ToolSpecification> tools,
                                                           Consumer<String> onToken) {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("t1").name("AskTool").arguments("{}").build();
            return CompletableFuture.completedFuture(AiMessage.from(List.of(request)));
        }
    }

    private static final class AskingTool extends BaseTool {
        private final AtomicInteger executions;
        private AskingTool(AtomicInteger executions) { this.executions = executions; }
        @Override public String name() { return "AskTool"; }
        @Override public String description() { return "requires approval"; }
        @Override public Category category() { return Category.UTILITY; }
        @Override public Visibility visibility() { return Visibility.ALL; }
        @Override public RiskLevel riskLevel() { return RiskLevel.CAUTION; }
        @Override public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
            return PermissionDecision.ask("confirm");
        }
        @Override public ToolResult execute(String arguments, PermissionContext context) {
            executions.incrementAndGet(); return ToolResult.success("done");
        }
        @Override public ToolSpecification getSpec() {
            return ToolSpecification.builder().name(name()).description(description()).build();
        }
    }
}
