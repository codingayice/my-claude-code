package cn.ayice.veyra.conversation.context.compaction;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.conversation.context.WorkingMessage;
import cn.ayice.veyra.llm.AIService;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSummaryGeneratorTest {

    @Test
    void generatesOnlyTheIncrementAfterPreviousCheckpoint() {
        CapturingAIService ai = new CapturingAIService(List.of("updated summary"));
        SessionSummaryGenerator generator = new SessionSummaryGenerator(ai, new ConversationChunker());
        StableHistorySnapshot snapshot = new StableHistorySnapshot(3, List.of(
                WorkingMessage.original(1, UserMessage.from("already covered")),
                WorkingMessage.original(2, UserMessage.from("new requirement")),
                WorkingMessage.original(3, AiMessage.from("new conclusion"))
        ));

        CheckpointCandidate candidate = generator.generate(
                snapshot,
                Optional.of(new CompactionCheckpoint("old summary", 1, 1))
        );

        assertEquals(3, candidate.coveredSequence());
        assertEquals("updated summary", candidate.summaryText());
        assertTrue(ai.prompts.get(0).contains("old summary"));
        assertTrue(ai.prompts.get(0).contains("new requirement"));
        assertFalse(ai.prompts.get(0).contains("already covered"));
    }

    @Test
    void retriesOnceWithShorterOutputWhenSummaryExceedsHardLimit() {
        CapturingAIService ai = new CapturingAIService(List.of("x".repeat(200), "short"));
        SessionSummaryGenerator generator = new SessionSummaryGenerator(
                ai,
                new ConversationChunker(),
                1_000,
                10,
                5
        );
        StableHistorySnapshot snapshot = new StableHistorySnapshot(1, List.of(
                WorkingMessage.original(1, UserMessage.from("task"))
        ));

        CheckpointCandidate candidate = generator.generate(snapshot, Optional.empty());

        assertEquals("short", candidate.summaryText());
        assertEquals(2, ai.prompts.size());
        assertTrue(ai.prompts.get(1).contains("summary-to-shorten"));
    }

    private static final class CapturingAIService extends AIService {
        private final ArrayDeque<String> responses;
        private final List<String> prompts = new ArrayList<>();

        private CapturingAIService(List<String> responses) {
            super(new AppConfig("__missing_session_summary_generator_test_config__.yaml"));
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            prompts.add(request.messages().toString());
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from(responses.removeFirst()))
                    .build();
        }
    }
}
