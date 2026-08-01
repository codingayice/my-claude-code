package cn.ayice.veyra.compaction;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.WorkingMessage;
import cn.ayice.veyra.llm.AIService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmSummaryCompactorTest {

    @Test
    void summarizesEverythingBeforeACompleteRecentUserTurn() {
        LlmSummaryCompactor compactor = new LlmSummaryCompactor(
                new StubAIService("summary"),
                new ConversationChunker(),
                100_000,
                2
        );
        List<WorkingMessage> history = List.of(
                WorkingMessage.original(1, UserMessage.from("old request")),
                WorkingMessage.original(2, AiMessage.from("old answer")),
                WorkingMessage.original(3, UserMessage.from("current request")),
                WorkingMessage.original(4, AiMessage.from("current answer"))
        );

        CompactionResult result = compactor.compact(history, CompactTrigger.MANUAL, 100);

        assertEquals(CompactStrategy.LLM_SUMMARY, result.strategy());
        assertEquals(2, result.checkpointCandidate().orElseThrow().coveredSequence());
        assertTrue(result.messages().get(0).message().toString().contains("CompactBoundary"));
        assertTrue(result.messages().stream().anyMatch(message -> message.message().toString().contains("current request")));
        assertFalse(result.messages().stream().anyMatch(message -> message.message().toString().contains("old request")));
    }

    @Test
    void keepsToolRequestAndResultTogetherInRecentHistory() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("tool-1")
                .name("Read")
                .arguments("{\"path\":\"a.txt\"}")
                .build();
        List<WorkingMessage> history = new ArrayList<>();
        history.add(WorkingMessage.original(1, UserMessage.from("old request")));
        history.add(WorkingMessage.original(2, AiMessage.from("old answer")));
        history.add(WorkingMessage.original(3, UserMessage.from("current request")));
        history.add(WorkingMessage.original(4, AiMessage.from(request)));
        history.add(WorkingMessage.original(5, ToolExecutionResultMessage.from("tool-1", "Read", "result")));
        LlmSummaryCompactor compactor = new LlmSummaryCompactor(
                new StubAIService("summary"),
                new ConversationChunker(),
                100_000,
                1
        );

        CompactionResult result = compactor.compact(history, CompactTrigger.AUTO, 100);

        assertTrue(result.messages().stream().anyMatch(message -> message.message() instanceof AiMessage ai
                && ai.hasToolExecutionRequests()));
        assertTrue(result.messages().stream().anyMatch(message -> message.message() instanceof ToolExecutionResultMessage));
    }

    private static final class StubAIService extends AIService {
        private final String response;

        private StubAIService(String response) {
            super(new AppConfig("__missing_llm_summary_compactor_test_config__.yaml"));
            this.response = response;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        }
    }
}
