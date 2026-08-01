package cn.ayice.veyra.memory.extraction;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.memory.MemoryFileStore;
import cn.ayice.veyra.memory.MemoryPaths;
import cn.ayice.veyra.memory.MemoryScope;
import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.subagent.AgentProfile;
import cn.ayice.veyra.subagent.SubagentRuntime;
import cn.ayice.veyra.subagent.SubagentToolCatalogs;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.subagent.AgentRunResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryExtractionCoordinatorTest {

    @TempDir
    Path tempDir;

    @Test
    void extractionUsesMemoryToolAndAdvancesCursorAfterSuccess() {
        MemoryService memory = memory();
        StubAIService ai = new StubAIService(List.of(
                AiMessage.from(List.of(rememberRequest())),
                AiMessage.from("记忆已更新")
        ));
        SubagentRuntime runtime = new SubagentRuntime(
                ai,
                new TestConfig(tempDir),
                null,
                null,
                null,
                SubagentToolCatalogs.factory(memory, Runnable::run)
        );
        MemoryExtractionCoordinator coordinator = new MemoryExtractionCoordinator(
                "session-1", memory, runtime, Runnable::run, 5);
        List<ChatMessage> messages = List.of(
                UserMessage.from("以后核心后端改动必须补测试"),
                AiMessage.from("收到，我会按这个要求执行。")
        );

        assertTrue(coordinator.extractNow(messages));

        assertEquals(2, coordinator.status().cursor());
        assertEquals("success", coordinator.status().lastResult());
        assertEquals("核心后端改动必须补测试", memory.show(MemoryScope.USER, "backend-tests").content());
        assertEquals(List.of("Memory"), ai.firstToolNames);
        assertTrue(ai.firstPrompt.contains("新对话片段"));
    }

    @Test
    void overlappingSubmissionsRunOneFlightAndOneLatestTrailingRequest() throws Exception {
        MemoryService memory = memory();
        var executor = Executors.newFixedThreadPool(2);
        BlockingRuntime runtime = new BlockingRuntime(memory, tempDir);
        try {
            MemoryExtractionCoordinator coordinator = new MemoryExtractionCoordinator(
                    "session-2", memory, runtime, executor, 5);
            coordinator.submit(List.of(UserMessage.from("first")), false);
            assertTrue(runtime.firstStarted.await(2, TimeUnit.SECONDS));

            coordinator.submit(List.of(UserMessage.from("first"), AiMessage.from("second")), false);
            coordinator.submit(List.of(
                    UserMessage.from("first"),
                    AiMessage.from("second"),
                    UserMessage.from("latest")
            ), false);
            runtime.releaseFirst.countDown();

            assertTrue(coordinator.awaitIdle(Duration.ofSeconds(3)));
            assertEquals(2, runtime.calls.get());
            assertEquals(3, coordinator.status().cursor());
            assertFalse(coordinator.status().pending());
            assertFalse(coordinator.status().running());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void failedExtractionDoesNotAdvanceCursor() {
        MemoryService memory = memory();
        FailingRuntime runtime = new FailingRuntime(memory, tempDir);
        MemoryExtractionCoordinator coordinator = new MemoryExtractionCoordinator(
                "session-3", memory, runtime, Runnable::run, 5);

        assertFalse(coordinator.extractNow(List.of(UserMessage.from("remember this"))));

        assertEquals(0, coordinator.status().cursor());
        assertEquals("MEMORY_EXTRACTION_FAILED", coordinator.status().lastErrorCode());
    }

    private MemoryService memory() {
        MemoryPaths paths = new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString());
        return new MemoryService(new MemoryFileStore(paths, 16 * 1024, 200, 25 * 1024, 200));
    }

    private static ToolExecutionRequest rememberRequest() {
        return ToolExecutionRequest.builder()
                .id("remember")
                .name("Memory")
                .arguments("""
                        {
                          "action":"remember",
                          "scope":"USER",
                          "type":"FEEDBACK",
                          "activation":"RELEVANT",
                          "id":"backend-tests",
                          "name":"后端测试反馈",
                          "description":"用户要求核心后端改动必须补测试",
                          "content":"核心后端改动必须补测试"
                        }
                        """)
                .build();
    }

    private static final class StubAIService extends AIService {
        private final List<AiMessage> responses;
        private int index;
        private String firstPrompt = "";
        private List<String> firstToolNames = List.of();

        private StubAIService(List<AiMessage> responses) {
            super(new AppConfig("__missing_memory_extraction_coordinator_test_config__.yaml"));
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            if (index == 0) {
                firstPrompt = request.messages().toString();
                firstToolNames = request.toolSpecifications().stream().map(spec -> spec.name()).toList();
            }
            AiMessage response = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return ChatResponse.builder().aiMessage(response).build();
        }
    }

    private static class BlockingRuntime extends SubagentRuntime {
        private final AtomicInteger calls = new AtomicInteger();
        private final CountDownLatch firstStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);

        private BlockingRuntime(MemoryService memory, Path workspace) {
            super(
                    null,
                    new TestConfig(workspace),
                    null,
                    null,
                    null,
                    SubagentToolCatalogs.factory(memory, Runnable::run)
            );
        }

        @Override
        public AgentRunResult run(
                AgentProfile profile,
                String prompt,
                PermissionContext parentPermissionContext,
                String agentId,
                String description
        ) {
            int call = calls.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                try {
                    releaseFirst.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return new AgentRunResult(agentId, profile.type(), "completed", "done", 1, 0);
        }
    }

    private static final class FailingRuntime extends BlockingRuntime {
        private FailingRuntime(MemoryService memory, Path workspace) {
            super(memory, workspace);
        }

        @Override
        public AgentRunResult run(
                AgentProfile profile,
                String prompt,
                PermissionContext parentPermissionContext,
                String agentId,
                String description
        ) {
            return new AgentRunResult(agentId, profile.type(), "error", "failed", 1, 0);
        }
    }

    private static final class TestConfig extends AppConfig {
        private final Path workspace;

        private TestConfig(Path workspace) {
            super("__missing_memory_extraction_coordinator_test_config__.yaml");
            this.workspace = workspace;
        }

        @Override
        public String getWorkspace() {
            return workspace.toString();
        }

        @Override
        public int getMaxRounds() {
            return 5;
        }
    }
}
