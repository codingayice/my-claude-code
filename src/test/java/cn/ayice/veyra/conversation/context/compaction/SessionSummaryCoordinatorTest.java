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
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionSummaryCoordinatorTest {

    @Test
    void coalescesConcurrentRequestsIntoTheLatestDirtySnapshot() {
        QueuedExecutor executor = new QueuedExecutor();
        SessionCheckpointState checkpointState = new SessionCheckpointState();
        SessionSummaryGenerator generator = new SessionSummaryGenerator(
                new StubAIService(),
                new ConversationChunker()
        );
        SessionSummaryCoordinator coordinator = new SessionSummaryCoordinator(
                generator,
                checkpointState,
                executor,
                new SessionSummaryConfig(1, 1, 1, 1, 12_000, 3_000, 1_800)
        );

        assertTrue(coordinator.submitStableSnapshot(snapshot(1)));
        assertTrue(coordinator.submitStableSnapshot(snapshot(2)));
        assertTrue(coordinator.submitStableSnapshot(snapshot(3)));
        assertEquals(1, executor.size());

        executor.runNext();
        assertEquals(1, executor.size());
        executor.runNext();

        assertEquals(3, checkpointState.current().orElseThrow().coveredSequence());
        assertEquals(2, checkpointState.current().orElseThrow().checkpointVersion());
    }

    private static StableHistorySnapshot snapshot(int endSequence) {
        java.util.ArrayList<WorkingMessage> messages = new java.util.ArrayList<>();
        for (int sequence = 1; sequence <= endSequence; sequence++) {
            messages.add(WorkingMessage.original(sequence, UserMessage.from("message " + sequence)));
        }
        return new StableHistorySnapshot(endSequence, messages);
    }

    private static final class QueuedExecutor implements Executor {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        int size() {
            return tasks.size();
        }

        void runNext() {
            tasks.remove().run();
        }
    }

    private static final class StubAIService extends AIService {
        private int calls;

        private StubAIService() {
            super(new AppConfig("__missing_session_summary_coordinator_test_config__.yaml"));
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            calls++;
            return ChatResponse.builder()
                    .aiMessage(AiMessage.from("summary " + calls))
                    .build();
        }
    }
}
