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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SummaryCompactorTest {

    @Test
    void summarizesEverythingBeforeACompleteRecentUserTurn() {
        SummaryCompactor compactor = compactor(new CapturingAIService(List.of("summary")), 2);
        List<WorkingMessage> history = List.of(
                WorkingMessage.original(1, UserMessage.from("old request")),
                WorkingMessage.original(2, AiMessage.from("old answer")),
                WorkingMessage.original(3, UserMessage.from("current request")),
                WorkingMessage.original(4, AiMessage.from("current answer"))
        );

        CompactionService.Result result = compactor.compact(history, CompactionService.Trigger.MANUAL, 100);

        assertEquals(CompactionService.Strategy.LLM_SUMMARY, result.strategy());
        assertEquals(2, result.summaryCandidate().orElseThrow().coveredSequence());
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

        CompactionService.Result result = compactor(new CapturingAIService(List.of("summary")), 1)
                .compact(history, CompactionService.Trigger.AUTO, 100);

        assertTrue(result.messages().stream().anyMatch(message -> message.message() instanceof AiMessage ai
                && ai.hasToolExecutionRequests()));
        assertTrue(result.messages().stream().anyMatch(message -> message.message() instanceof ToolExecutionResultMessage));
    }

    @Test
    void generatesOnlyTheIncrementAfterPreviousSessionSummary() {
        CapturingAIService ai = new CapturingAIService(List.of("updated summary"));
        SummaryCompactor compactor = new SummaryCompactor(ai);
        BackgroundSummaryScheduler.Snapshot snapshot = new BackgroundSummaryScheduler.Snapshot(3, List.of(
                WorkingMessage.original(1, UserMessage.from("already covered")),
                WorkingMessage.original(2, UserMessage.from("new requirement")),
                WorkingMessage.original(3, AiMessage.from("new conclusion"))
        ));

        SessionSummaryState.SummaryCandidate candidate = compactor.generateSessionSummary(
                snapshot,
                Optional.of(new SessionSummaryState.SummarySnapshot("old summary", 1, 1))
        );

        assertEquals(3, candidate.coveredSequence());
        assertEquals("updated summary", candidate.summaryText());
        assertTrue(ai.prompts.get(0).contains("old summary"));
        assertTrue(ai.prompts.get(0).contains("new requirement"));
        assertFalse(ai.prompts.get(0).contains("already covered"));
    }

    @Test
    void retriesOnceWithShorterOutputWhenSessionSummaryExceedsLimit() {
        CapturingAIService ai = new CapturingAIService(List.of("x".repeat(200), "short"));
        SummaryCompactor compactor = new SummaryCompactor(ai, 100_000, 10, 1_000, 10, 5);
        BackgroundSummaryScheduler.Snapshot snapshot = new BackgroundSummaryScheduler.Snapshot(1, List.of(
                WorkingMessage.original(1, UserMessage.from("task"))
        ));

        SessionSummaryState.SummaryCandidate candidate = compactor.generateSessionSummary(snapshot, Optional.empty());

        assertEquals("short", candidate.summaryText());
        assertEquals(2, ai.prompts.size());
        assertTrue(ai.prompts.get(1).contains("summary-to-shorten"));
    }

    @Test
    void chunksOnlyBeforeRealUserTurns() {
        List<WorkingMessage> messages = List.of(
                WorkingMessage.original(1, UserMessage.from("first " + "x".repeat(100))),
                WorkingMessage.original(2, AiMessage.from("answer one")),
                WorkingMessage.original(3, UserMessage.from("second " + "y".repeat(100))),
                WorkingMessage.original(4, AiMessage.from("answer two"))
        );

        List<List<WorkingMessage>> chunks = SummaryCompactor.split(messages, 40);

        assertEquals(2, chunks.size());
        assertInstanceOf(UserMessage.class, chunks.get(0).get(0).message());
        assertInstanceOf(UserMessage.class, chunks.get(1).get(0).message());
    }

    @Test
    void promptRequiresMarkdownWithoutAnalysisOrToolCalls() {
        String prompt = SummaryCompactor.buildChunkSummaryPrompt("用户: 示例任务");

        assertTrue(prompt.contains("## 当前目标"));
        assertTrue(prompt.contains("## 用户约束"));
        assertTrue(prompt.contains("## 下一步"));
        assertTrue(prompt.contains("不要调用工具"));
        assertTrue(prompt.contains("不要输出分析过程"));
        assertFalse(prompt.contains("<analysis>"));
        assertFalse(prompt.contains("<summary>"));
        assertFalse(prompt.contains("Your task is"));
    }

    private static SummaryCompactor compactor(CapturingAIService ai, int keepRecentMessages) {
        return new SummaryCompactor(ai, 100_000, keepRecentMessages, 12_000, 3_000, 1_800);
    }

    private static final class CapturingAIService extends AIService {
        private final ArrayDeque<String> responses;
        private final List<String> prompts = new ArrayList<>();

        private CapturingAIService(List<String> responses) {
            super(new AppConfig("__missing_summary_compactor_test_config__.yaml"));
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            prompts.add(request.messages().toString());
            return ChatResponse.builder().aiMessage(AiMessage.from(responses.removeFirst())).build();
        }
    }
}
