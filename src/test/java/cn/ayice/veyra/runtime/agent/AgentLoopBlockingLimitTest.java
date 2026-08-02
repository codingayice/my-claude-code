package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.compaction.CheckpointState;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import cn.ayice.veyra.tool.state.TodoManager;
import cn.ayice.veyra.tool.state.FileStateCache;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopBlockingLimitTest {

    @TempDir
    Path tempDir;

    @Test
    void stopsBeforeModelCallWhenContextReachesBlockingLimit() {
        RecordingSink sink = new RecordingSink();
        CountingAIService ai = new CountingAIService();
        AgentLoop loop = createLoop(ai, sink);

        String result = loop.process("x".repeat(120_000));

        assertEquals(0, ai.streamingCalls);
        assertTrue(result.contains("上下文已接近模型上限"));
        assertEquals(0, sink.eventsOfType("context.warning").size());

        Event completed = sink.eventsOfType("run.completed").get(0);
        assertEquals("blocking_limit", completed.payload.get("reason"));
        assertTrue(String.valueOf(completed.payload.get("content")).contains("上下文已接近模型上限"));
    }

    private AgentLoop createLoop(AIService ai, AgentEventSink sink) {
        AppConfig config = new AppConfig("__missing_agent_loop_blocking_limit_test_config__.yaml");
        ToolCatalog catalog = ToolCatalog.create(List.of(), new FileStateCache());
        CompactionConfig compactConfig = new CompactionConfig(40_000, 1, false, true, null, true);
        ContextService contextBuilder = new ContextService(
                catalog.specifications(), catalog.descriptions(), config, null, compactConfig.contextTokenBudget());
        return new AgentLoop(
                ai,
                catalog,
                contextBuilder,
                null,
                new ToolExecutionConfirmation() {
                    @Override
                    public Choice ask(ToolExecutionRequest req, String reason) {
                        return Choice.ALLOW_ONCE;
                    }
                },
                new PermissionContextStore(PermissionContext.builder()
                        .mode(PermissionMode.AUTO_APPROVE)
                        .workingDir(tempDir)
                        .addAllowedDirectory(tempDir)
                        .build()),
                new TodoManager(sink::emit),
                compactConfig,
                10,
                null,
                sink,
                new CheckpointState(),
                null,
                new FileStateCache(),
                120_000,
                null,
                List.of(),
                message -> {},
                java.util.concurrent.ForkJoinPool.commonPool()
        );
    }

    private record Event(String type, Map<String, Object> payload) {
    }

    private static final class RecordingSink implements AgentEventSink {
        private final List<Event> events = new ArrayList<>();

        @Override
        public void emit(String type, Map<String, Object> payload) {
            events.add(new Event(type, payload));
        }

        private List<Event> eventsOfType(String type) {
            return events.stream().filter(event -> event.type.equals(type)).toList();
        }
    }

    private static final class CountingAIService extends AIService {
        private int streamingCalls = 0;

        private CountingAIService() {
            super(new AppConfig("__missing_agent_loop_blocking_limit_test_config__.yaml"));
        }

        @Override
        public CompletableFuture<AiMessage> streamingChat(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecs,
                Consumer<String> onToken
        ) {
            streamingCalls++;
            return CompletableFuture.completedFuture(AiMessage.from("should not be called"));
        }
    }
}
