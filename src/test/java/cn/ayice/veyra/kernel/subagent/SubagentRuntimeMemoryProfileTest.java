package cn.ayice.veyra.kernel.subagent;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.conversation.memory.MemoryFileStore;
import cn.ayice.veyra.conversation.memory.MemoryPaths;
import cn.ayice.veyra.conversation.memory.MemoryScope;
import cn.ayice.veyra.conversation.memory.MemoryService;
import cn.ayice.veyra.kernel.event.AgentEventSink;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionMode;
import cn.ayice.veyra.tooling.task.AgentRunResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentRuntimeMemoryProfileTest {

    @TempDir
    Path tempDir;

    @Test
    void memoryExtractionProfilePersistsThroughMemoryToolOnly() {
        MemoryService memory = memory();
        SequencedAIService ai = new SequencedAIService(List.of(
                AiMessage.from(List.of(rememberRequest("remember-memory"))),
                AiMessage.from("记忆已更新")
        ));
        SubagentRuntime runtime = new SubagentRuntime(
                ai, new TestConfig(tempDir), memory, java.util.concurrent.ForkJoinPool.commonPool());

        AgentRunResult result = runtime.run(
                AgentProfiles.memoryExtraction(5),
                "保存用户测试偏好",
                parentContext(tempDir),
                "memory-test",
                "长期记忆提取"
        );

        assertEquals("completed", result.status());
        assertEquals("核心后端改动必须补测试", memory.show(MemoryScope.USER, "backend-test-feedback").content());
        assertEquals(List.of("Memory"), ai.firstToolNames);
    }

    @Test
    void memoryExtractionProfileCannotSeeGenericFileOrShellTools() {
        MemoryService memory = memory();
        SequencedAIService ai = new SequencedAIService(List.of(AiMessage.from("没有需要保存的记忆")));
        SubagentRuntime runtime = new SubagentRuntime(
                ai, new TestConfig(tempDir), memory, java.util.concurrent.ForkJoinPool.commonPool());

        AgentRunResult result = runtime.run(
                AgentProfiles.memoryExtraction(5),
                "检查最近对话",
                parentContext(tempDir),
                "memory-test",
                "长期记忆提取"
        );

        assertEquals("completed", result.status());
        assertFalse(ai.firstToolNames.contains("Read"));
        assertFalse(ai.firstToolNames.contains("Write"));
        assertFalse(ai.firstToolNames.contains("Edit"));
        assertFalse(ai.firstToolNames.contains("bash"));
    }

    @Test
    void memoryExtractionProfileDoesNotEmitTranscriptEvents() {
        MemoryService memory = memory();
        CapturingEventSink eventSink = new CapturingEventSink();
        SequencedAIService ai = new SequencedAIService(List.of(AiMessage.from("没有需要保存的记忆")));
        SubagentRuntime runtime = new SubagentRuntime(
                ai,
                new TestConfig(tempDir),
                memory,
                null,
                eventSink,
                java.util.concurrent.ForkJoinPool.commonPool()
        );

        AgentRunResult result = runtime.run(
                AgentProfiles.memoryExtraction(5),
                "检查最近对话",
                parentContext(tempDir),
                "memory-test",
                "长期记忆提取"
        );

        assertEquals("completed", result.status());
        assertTrue(eventSink.events.isEmpty());
    }

    private MemoryService memory() {
        MemoryPaths paths = new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString());
        return new MemoryService(new MemoryFileStore(paths, 16 * 1024, 200, 25 * 1024, 200));
    }

    private static PermissionContext parentContext(Path root) {
        return PermissionContext.builder()
                .mode(PermissionMode.AUTO_APPROVE)
                .workingDir(root)
                .addAllowedDirectory(root)
                .build();
    }

    private static ToolExecutionRequest rememberRequest(String id) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name("Memory")
                .arguments("""
                        {
                          "action":"remember",
                          "scope":"USER",
                          "type":"FEEDBACK",
                          "activation":"RELEVANT",
                          "id":"backend-test-feedback",
                          "name":"后端测试反馈",
                          "description":"用户要求核心后端改动必须补测试",
                          "content":"核心后端改动必须补测试"
                        }
                        """)
                .build();
    }

    private static final class SequencedAIService extends AIService {
        private final List<AiMessage> responses;
        private int index;
        private List<String> firstToolNames = List.of();

        private SequencedAIService(List<AiMessage> responses) {
            super(new AppConfig("__missing_agent_runtime_memory_profile_test_config__.yaml"));
            this.responses = responses;
        }

        @Override
        public ChatResponse chat(ChatRequest request) {
            if (index == 0) {
                firstToolNames = request.toolSpecifications().stream().map(spec -> spec.name()).toList();
            }
            AiMessage message = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return ChatResponse.builder().aiMessage(message).build();
        }
    }

    private static final class TestConfig extends AppConfig {
        private final Path workspace;

        private TestConfig(Path workspace) {
            super("__missing_agent_runtime_memory_profile_test_config__.yaml");
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

    private static final class CapturingEventSink implements AgentEventSink {
        private final List<String> events = new ArrayList<>();

        @Override
        public void emit(String type, Map<String, Object> payload) {
            events.add(type);
        }
    }
}
