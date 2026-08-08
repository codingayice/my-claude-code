package cn.ayice.veyra.runtime.chat;

import cn.ayice.veyra.llm.ChatStreamer;

import cn.ayice.veyra.session.persistence.JournalMessageRecorder;
import cn.ayice.veyra.session.event.AgentEventSink;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatLoopTest {

    @Test
    void streamsThinkingAndAnswerTokensSeparately() {
        RecordingSink sink = new RecordingSink();
        RecordingChatStreamer streamer = new RecordingChatStreamer("先判断问题范围", "这是回答");
        ChatLoop loop = new ChatLoop(streamer, sink, 120_000, List.of(), new RecordingTranscriptRecorder());

        String result = loop.process("你好");

        assertEquals("这是回答", result);
        assertEquals(1, streamer.calls.size());
        assertEquals(1, streamer.calls.get(0).size());
        assertEquals("user", streamer.calls.get(0).get(0).type().name().toLowerCase());

        assertEquals("user.message", sink.events.get(0).type);
        assertEquals("assistant.thinking.token", sink.eventsOfType("assistant.thinking.token").get(0).type);
        assertEquals("先判断问题范围", sink.eventsOfType("assistant.thinking.token").get(0).payload.get("text"));
        assertEquals("assistant.token", sink.eventsOfType("assistant.token").get(0).type);
        assertEquals("这是回答", sink.eventsOfType("assistant.token").get(0).payload.get("text"));
        Event completed = sink.eventsOfType("assistant.message.completed").get(0);
        assertEquals("先判断问题范围", completed.payload.get("thinking"));
        assertEquals("这是回答", completed.payload.get("text"));
        assertEquals(false, completed.payload.get("hasToolRequests"));
        assertEquals("run.completed", sink.eventsOfType("run.completed").get(0).type);
    }

    @Test
    void preservesChatHistoryAcrossTurns() {
        RecordingSink sink = new RecordingSink();
        RecordingChatStreamer streamer = new RecordingChatStreamer("思考", "回答");
        ChatLoop loop = new ChatLoop(streamer, sink, 120_000, List.of(), new RecordingTranscriptRecorder());

        loop.process("第一轮");
        loop.process("第二轮");

        assertEquals(2, streamer.calls.size());
        assertEquals(1, streamer.calls.get(0).size());
        assertEquals(3, streamer.calls.get(1).size());
        assertFalse(streamer.calls.get(1).get(1) instanceof dev.langchain4j.data.message.ToolExecutionResultMessage);
    }

    @Test
    void emitsRunFailedWhenChatModelFails() {
        RecordingSink sink = new RecordingSink();
        FailingChatStreamer streamer = new FailingChatStreamer();
        ChatLoop loop = new ChatLoop(streamer, sink, 120_000, List.of(), new RecordingTranscriptRecorder());

        String result = loop.process("你好");

        assertTrue(result.contains("LLM 调用失败"));
        assertEquals("run.failed", sink.eventsOfType("run.failed").get(0).type);
        assertEquals("temporary outage", sink.eventsOfType("run.failed").get(0).payload.get("error"));
    }

    @Test
    void startsFromRestoredHistoryAndRecordsNewMessages() {
        RecordingSink sink = new RecordingSink();
        RecordingChatStreamer streamer = new RecordingChatStreamer("思考", "新回答");
        RecordingTranscriptRecorder recorder = new RecordingTranscriptRecorder();
        ChatLoop loop = new ChatLoop(
                streamer,
                sink,
                120_000,
                List.of(UserMessage.from("旧问题"), AiMessage.from("旧回答")),
                recorder
        );

        loop.process("新问题");

        assertEquals(3, streamer.calls.get(0).size());
        assertEquals("旧问题", ((UserMessage) streamer.calls.get(0).get(0)).singleText());
        assertEquals("旧回答", ((AiMessage) streamer.calls.get(0).get(1)).text());
        assertEquals("新问题", ((UserMessage) streamer.calls.get(0).get(2)).singleText());
        assertEquals(2, recorder.messages.size());
        assertEquals("新问题", ((UserMessage) recorder.messages.get(0)).singleText());
        assertEquals("新回答", ((AiMessage) recorder.messages.get(1)).text());
    }

    private record Event(String type, Map<String, Object> payload) {
    }

    private static final class RecordingTranscriptRecorder implements JournalMessageRecorder {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public void record(ChatMessage message) {
            messages.add(message);
        }
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

    private static final class RecordingChatStreamer implements ChatStreamer {
        private final String thinking;
        private final String answer;
        private final List<List<ChatMessage>> calls = new ArrayList<>();

        private RecordingChatStreamer(String thinking, String answer) {
            this.thinking = thinking;
            this.answer = answer;
        }

        @Override
        public CompletableFuture<AiMessage> streamingChatOnly(
                List<ChatMessage> messages,
                Consumer<String> onThinking,
                Consumer<String> onToken
        ) {
            calls.add(new ArrayList<>(messages));
            onThinking.accept(thinking);
            onToken.accept(answer);
            return CompletableFuture.completedFuture(AiMessage.builder()
                    .thinking(thinking)
                    .text(answer)
                    .build());
        }
    }

    private static final class FailingChatStreamer implements ChatStreamer {
        @Override
        public CompletableFuture<AiMessage> streamingChatOnly(
                List<ChatMessage> messages,
                Consumer<String> onThinking,
                Consumer<String> onToken
        ) {
            CompletableFuture<AiMessage> failed = new CompletableFuture<>();
            failed.completeExceptionally(new RuntimeException("temporary outage"));
            return failed;
        }
    }
}
