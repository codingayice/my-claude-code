package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.runtime.PendingInputQueue;
import cn.ayice.veyra.runtime.RunMode;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.session.persistence.JournalMessageRecorder;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.tool.state.TodoManager;
import cn.ayice.veyra.compaction.SessionSummaryState;
import cn.ayice.veyra.tool.state.FileStateCache;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopRecoveryTest {

    @TempDir
    Path tempDir;

    @Test
    void retriesModelFailureAndThenSucceeds() {
        RecordingSink sink = new RecordingSink();
        FailingThenSuccessfulAIService ai = new FailingThenSuccessfulAIService();
        AgentLoop loop = createLoop(ai, sink, tempDir);

        String result = loop.process("hello");

        assertEquals("ok", result);
        assertEquals(2, ai.calls);
        assertFalse(ai.retryMessages.toString().contains("LLM 调用失败"));
        assertFalse(ai.retryMessages.toString().contains("temporary outage"));
    }

    @Test
    void failsRunWhenStreamingModelNeverCompletes() {
        RecordingSink sink = new RecordingSink();
        HangingAIService ai = new HangingAIService();
        AgentLoop loop = createLoop(ai, sink, tempDir, 25);

        String result = loop.process("trigger timeout");

        assertTrue(result.contains("LLM 调用连续失败次数过多"));
        assertEquals(3, ai.calls);
        assertEquals("run.failed", sink.eventsOfType("run.failed").get(0).type);
    }

    @Test
    void compactsAndRetriesOnceWhenPromptTooLong() {
        RecordingSink sink = new RecordingSink();
        PromptTooLongAfterWarmupAIService ai = new PromptTooLongAfterWarmupAIService(7);
        AgentLoop loop = createLoop(ai, sink, tempDir);

        for (int i = 0; i < 6; i++) {
            loop.process("seed " + i + " " + "x".repeat(200));
        }

        String result = loop.process("trigger oversized prompt");

        assertEquals("ok", result);
        assertEquals(8, ai.streamingCalls);
        assertEquals(1, ai.summaryCalls);
        assertTrue(ai.lastRetryMessages.toString().contains("reactive summary"));
    }

    @Test
    void startsFromRestoredHistoryAndRecordsNewMessages() {
        RecordingSink sink = new RecordingSink();
        RecordingAIService ai = new RecordingAIService();
        RecordingTranscriptRecorder recorder = new RecordingTranscriptRecorder();
        AgentLoop loop = createLoop(
                ai,
                sink,
                tempDir,
                120_000,
                List.of(UserMessage.from("旧问题"), AiMessage.from("旧回答")),
                recorder
        );

        String result = loop.process("新问题");

        assertEquals("ok", result);
        List<ChatMessage> tail = ai.lastMessages.subList(ai.lastMessages.size() - 3, ai.lastMessages.size());
        assertEquals("旧问题", ((UserMessage) tail.get(0)).singleText());
        assertEquals("旧回答", ((AiMessage) tail.get(1)).text());
        assertEquals("新问题", ((UserMessage) tail.get(2)).singleText());
        assertEquals(2, recorder.messages.size());
        assertEquals("新问题", ((UserMessage) recorder.messages.get(0)).singleText());
        assertEquals("ok", ((AiMessage) recorder.messages.get(1)).text());
    }

    @Test
    void injectsSteeringInputBeforeTheNextModelRequest() {
        RecordingSink sink = new RecordingSink();
        RecordingAIService ai = new RecordingAIService();
        RecordingTranscriptRecorder recorder = new RecordingTranscriptRecorder();
        AgentLoop loop = createLoop(ai, sink, tempDir, 120_000, List.of(), recorder);
        PendingInputQueue.Message pending = loop.pendingInputs().addFollowup("改为只分析，不修改", RunMode.AGENT);
        assertTrue(loop.pendingInputs().steer(pending.id()));

        String result = loop.process("检查这个问题");

        assertEquals("ok", result);
        List<UserMessage> userMessages = ai.lastMessages.stream()
                .filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast)
                .toList();
        assertEquals(List.of("检查这个问题", "改为只分析，不修改"),
                userMessages.stream().map(UserMessage::singleText).toList());
        assertEquals(1, sink.eventsOfType("input.applied").size());
        Event steeringEvent = sink.eventsOfType("user.message").get(1);
        assertEquals(pending.id(), steeringEvent.payload().get("messageId"));
        assertEquals("steer", steeringEvent.payload().get("mode"));
        assertEquals(3, recorder.messages.size());
    }

    private static AgentLoop createLoop(AIService ai, AgentEventSink sink, Path tempDir) {
        return createLoop(ai, sink, tempDir, 120_000);
    }

    private static AgentLoop createLoop(AIService ai, AgentEventSink sink, Path tempDir, long modelCallTimeoutMs) {
        return createLoop(ai, sink, tempDir, modelCallTimeoutMs, List.of(), new RecordingTranscriptRecorder());
    }

    private static AgentLoop createLoop(
            AIService ai,
            AgentEventSink sink,
            Path tempDir,
            long modelCallTimeoutMs,
            List<ChatMessage> initialHistory,
            JournalMessageRecorder transcriptRecorder
    ) {
        AppConfig config = new AppConfig("__missing_agent_loop_recovery_test_config__.yaml");
        ToolCatalog catalog = ToolCatalog.create(List.of(), new FileStateCache());
        CompactionConfig compactConfig = new CompactionConfig(1_013_000, 4096, true, true, null, true);
        ContextService contextBuilder = new ContextService(
                catalog.specifications(), catalog.descriptions(), config, null, compactConfig.contextTokenBudget());
        return new AgentLoop(
                ai,
                catalog,
                contextBuilder,
                null,
                new PermissionContextStore(PermissionContext.builder()
                        .mode(PermissionMode.AUTO_APPROVE)
                        .allowedDirectories(List.of())
                        .build()),
                new TodoManager(sink::emit),
                compactConfig,
                10,
                null,
                sink,
                new SessionSummaryState(),
                null,
                new FileStateCache(),
                modelCallTimeoutMs,
                null,
                initialHistory,
                transcriptRecorder,
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

    private static final class RecordingTranscriptRecorder implements JournalMessageRecorder {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public void record(ChatMessage message) {
            messages.add(message);
        }
    }

    private static final class RecordingAIService extends AIService {
        private List<ChatMessage> lastMessages = List.of();

        private RecordingAIService() {
            super(new AppConfig("__missing_agent_loop_recovery_test_config__.yaml"));
        }

        @Override
        public CompletableFuture<AiMessage> streamingChat(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecs,
                Consumer<String> onToken
        ) {
            lastMessages = List.copyOf(messages);
            onToken.accept("ok");
            return CompletableFuture.completedFuture(AiMessage.from("ok"));
        }
    }

    private static final class FailingThenSuccessfulAIService extends AIService {
        private int calls = 0;
        private List<ChatMessage> retryMessages = List.of();

        private FailingThenSuccessfulAIService() {
            super(new AppConfig("__missing_agent_loop_recovery_test_config__.yaml"));
        }

        @Override
        public CompletableFuture<AiMessage> streamingChat(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecs,
                Consumer<String> onToken
        ) {
            calls++;
            if (calls == 1) {
                CompletableFuture<AiMessage> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("temporary outage"));
                return failed;
            }
            retryMessages = List.copyOf(messages);
            onToken.accept("ok");
            return CompletableFuture.completedFuture(AiMessage.from("ok"));
        }
    }

    private static final class PromptTooLongAfterWarmupAIService extends AIService {
        private final int failOnCall;
        private int streamingCalls = 0;
        private int summaryCalls = 0;
        private List<ChatMessage> lastRetryMessages = List.of();

        private PromptTooLongAfterWarmupAIService(int failOnCall) {
            super(new AppConfig("__missing_agent_loop_recovery_test_config__.yaml"));
            this.failOnCall = failOnCall;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            summaryCalls++;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("reactive summary"))
                    .build();
        }

        @Override
        public CompletableFuture<AiMessage> streamingChat(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecs,
                Consumer<String> onToken
        ) {
            streamingCalls++;
            if (streamingCalls == failOnCall) {
                CompletableFuture<AiMessage> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("prompt too long"));
                return failed;
            }
            if (streamingCalls > failOnCall) {
                lastRetryMessages = List.copyOf(messages);
            }
            onToken.accept("ok");
            return CompletableFuture.completedFuture(AiMessage.from("ok"));
        }
    }

    private static final class HangingAIService extends AIService {
        private int calls = 0;

        private HangingAIService() {
            super(new AppConfig("__missing_agent_loop_recovery_test_config__.yaml"));
        }

        @Override
        public CompletableFuture<AiMessage> streamingChat(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecs,
                Consumer<String> onToken
        ) {
            calls++;
            return new CompletableFuture<>();
        }
    }
}
