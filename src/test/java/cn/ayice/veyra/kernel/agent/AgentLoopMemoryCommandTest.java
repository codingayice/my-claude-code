package cn.ayice.veyra.kernel.agent;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.conversation.context.ContextBuilder;
import cn.ayice.veyra.conversation.context.compaction.AutoCompactConfig;
import cn.ayice.veyra.conversation.context.compaction.SessionCheckpointState;
import cn.ayice.veyra.kernel.memory.MemoryExtractionCoordinator;
import cn.ayice.veyra.conversation.memory.MemoryFileStore;
import cn.ayice.veyra.conversation.memory.MemoryPaths;
import cn.ayice.veyra.conversation.memory.MemoryScope;
import cn.ayice.veyra.conversation.memory.MemoryService;
import cn.ayice.veyra.kernel.event.AgentEventSink;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tooling.ToolDispatcher;
import cn.ayice.veyra.tooling.ToolExecutionConfirmation;
import cn.ayice.veyra.tooling.ToolRegistry;
import cn.ayice.veyra.kernel.memory.MemoryTool;
import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionContextStore;
import cn.ayice.veyra.tooling.permission.PermissionMode;
import cn.ayice.veyra.tooling.state.TodoManager;
import cn.ayice.veyra.tooling.state.FileStateCache;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentLoopMemoryCommandTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitMemoryToolWriteSkipsBackgroundExtractionForCurrentRange() {
        MemoryService memory = memory();
        ToolRegistry registry = new ToolRegistry();
        ToolDispatcher dispatcher = new ToolDispatcher();
        MemoryTool memoryTool = new MemoryTool(memory);
        registry.register(memoryTool);
        dispatcher.register(memoryTool);
        AutoCompactConfig compactConfig = new AutoCompactConfig(1_000_000, 4_096, true, true, null, true);
        ContextBuilder contextBuilder = new ContextBuilder(
                registry.getAllSpecs(),
                registry.getDescriptions(),
                new AppConfig("__missing_agent_loop_memory_command_test_config__.yaml"),
                null,
                compactConfig
        );
        SequencedAIService ai = new SequencedAIService();
        CountingExtractionCoordinator extraction = new CountingExtractionCoordinator(memory);
        AgentLoop loop = new AgentLoop(
                ai,
                dispatcher,
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
                new TodoManager(null),
                compactConfig,
                10,
                null,
                AgentEventSink.NOOP,
                new SessionCheckpointState(),
                null,
                new FileStateCache(),
                120_000,
                extraction,
                List.of(),
                message -> { },
                Runnable::run
        );

        String result = loop.process("记住这个项目偏好");

        assertEquals("final answer", result);
        assertEquals(1, extraction.submissions.get());
        assertTrue(extraction.lastSkip);
        assertEquals("工具批次必须完整结束", memory.show(MemoryScope.PROJECT, "tool-batch-barrier").content());
    }

    private MemoryService memory() {
        MemoryPaths paths = new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString());
        return new MemoryService(new MemoryFileStore(paths, 16 * 1024, 200, 25 * 1024, 200));
    }

    private static final class SequencedAIService extends AIService {
        private int calls;

        private SequencedAIService() {
            super(new AppConfig("__missing_agent_loop_memory_command_test_config__.yaml"));
        }

        @Override
        public CompletableFuture<AiMessage> streamingChat(
                List<ChatMessage> messages,
                List<ToolSpecification> toolSpecs,
                Consumer<String> onToken
        ) {
            calls++;
            if (calls == 1) {
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id("remember-memory")
                        .name("Memory")
                        .arguments("""
                                {
                                  "action":"remember",
                                  "scope":"PROJECT",
                                  "type":"CONTEXT",
                                  "activation":"RELEVANT",
                                  "id":"tool-batch-barrier",
                                  "name":"工具批次屏障",
                                  "description":"工具可以并行但必须全部完成后进入下一轮",
                                  "content":"工具批次必须完整结束"
                                }
                                """)
                        .build();
                return CompletableFuture.completedFuture(AiMessage.from(List.of(request)));
            }
            return CompletableFuture.completedFuture(AiMessage.from("final answer"));
        }
    }

    private static final class CountingExtractionCoordinator extends MemoryExtractionCoordinator {
        private final AtomicInteger submissions = new AtomicInteger();
        private boolean lastSkip;

        private CountingExtractionCoordinator(MemoryService memory) {
            super("test-session", memory, null, Runnable::run, 5);
        }

        @Override
        public void submit(List<ChatMessage> messages, boolean mainAgentWroteMemory) {
            submissions.incrementAndGet();
            lastSkip = mainAgentWroteMemory;
        }
    }
}
