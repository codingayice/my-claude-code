package cn.ayice.veyra.compaction;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.ContextBudgetService;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.context.FinalRequestValidator;
import cn.ayice.veyra.context.WorkingMessage;
import cn.ayice.veyra.compaction.AutoCompactConfig;
import cn.ayice.veyra.compaction.CheckpointCandidate;
import cn.ayice.veyra.compaction.CompactStrategy;
import cn.ayice.veyra.compaction.CompactTrigger;
import cn.ayice.veyra.compaction.ConversationChunker;
import cn.ayice.veyra.compaction.LlmSummaryCompactor;
import cn.ayice.veyra.compaction.MicroCompactor;
import cn.ayice.veyra.compaction.SessionCheckpointState;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tool.state.FileStateCache;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactionServiceTest {

    @Test
    void autoUsesCommittedSessionSummaryBeforeCallingLlmSummary() {
        AutoCompactConfig config = new AutoCompactConfig(14_500, 1, true, true, null, true);
        SessionCheckpointState checkpointState = new SessionCheckpointState();
        checkpointState.commit(new CheckpointCandidate("checkpoint summary", 1));
        StubAIService ai = new StubAIService("unused");
        CompactionService preparer = preparer(config, checkpointState, ai, new FileStateCache());
        List<WorkingMessage> history = List.of(
                WorkingMessage.original(1, UserMessage.from("x".repeat(8_000))),
                WorkingMessage.original(2, UserMessage.from("current request"))
        );

        CompactionService.PreparedWorkingTurn prepared = preparer.prepareWorking(
                history,
                CompactTrigger.AUTO,
                1,
                Path.of(".")
        );

        assertEquals(CompactStrategy.SESSION_SUMMARY, prepared.strategy());
        assertEquals(0, ai.calls);
        assertTrue(prepared.request().messages().toString().contains("checkpoint summary"));
        assertTrue(prepared.request().messages().toString().contains("current request"));
        assertFalse(prepared.request().messages().toString().contains("x".repeat(100)));
    }

    @Test
    void manualCompactionRunsBelowThresholdAndCommitsValidatedCandidate() {
        AutoCompactConfig config = new AutoCompactConfig(1_000_000, 4_096, true, true, null, true);
        SessionCheckpointState checkpointState = new SessionCheckpointState();
        StubAIService ai = new StubAIService("manual summary");
        FileStateCache fileStateCache = new FileStateCache();
        Path modified = Path.of("modified.txt").toAbsolutePath().normalize();
        fileStateCache.recordModified(modified);
        CompactionService preparer = preparer(config, checkpointState, ai, fileStateCache);
        List<WorkingMessage> history = new ArrayList<>();
        for (int sequence = 1; sequence <= 12; sequence++) {
            history.add(WorkingMessage.original(sequence, UserMessage.from("message " + sequence)));
        }

        CompactionService.PreparedWorkingTurn prepared = preparer.prepareWorking(
                history,
                CompactTrigger.MANUAL,
                0,
                Path.of(".")
        );

        assertEquals(CompactStrategy.LLM_SUMMARY, prepared.strategy());
        assertEquals(1, ai.calls);
        assertEquals(2, checkpointState.current().orElseThrow().coveredSequence());
        assertTrue(prepared.request().messages().toString().contains("context-restoration"));
        assertTrue(prepared.request().messages().toString().contains(modified.toString()));
    }

    private static CompactionService preparer(
            AutoCompactConfig config,
            SessionCheckpointState checkpointState,
            StubAIService ai,
            FileStateCache fileStateCache
    ) {
        ContextService contextBuilder = new MinimalContextService(config);
        return new CompactionService(
                contextBuilder,
                config,
                new ContextBudgetService(config),
                new MicroCompactor(),
                checkpointState,
                new LlmSummaryCompactor(ai, new ConversationChunker()),
                new FinalRequestValidator(),
                fileStateCache::recentModifiedPaths
        );
    }

    private static final class MinimalContextService extends ContextService {
        private MinimalContextService(AutoCompactConfig config) {
            super(
                    List.of(),
                    Map.of(),
                    new AppConfig("__missing_agent_turn_preparer_enhanced_test_config__.yaml"),
                    null,
                    config
            );
        }

        @Override
        public ChatRequest buildWorking(List<WorkingMessage> history, Path workingDir) {
            List<ChatMessage> messages = history.stream().map(WorkingMessage::message).toList();
            return ChatRequest.builder().messages(messages).build();
        }
    }

    private static final class StubAIService extends AIService {
        private final String response;
        private int calls;

        private StubAIService(String response) {
            super(new AppConfig("__missing_agent_turn_preparer_enhanced_test_config__.yaml"));
            this.response = response;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls++;
            return ChatResponse.builder().aiMessage(AiMessage.from(response)).build();
        }
    }
}
