package cn.ayice.veyra.context;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.compaction.AutoCompactConfig;
import cn.ayice.veyra.memory.MemoryActivation;
import cn.ayice.veyra.memory.MemoryContextBuilder;
import cn.ayice.veyra.memory.MemoryFileStore;
import cn.ayice.veyra.memory.MemoryPaths;
import cn.ayice.veyra.memory.MemoryRecallService;
import cn.ayice.veyra.memory.MemoryScope;
import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.memory.MemoryType;
import cn.ayice.veyra.memory.RememberMemoryCommand;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextServiceMemoryTest {

    @TempDir
    Path tempDir;

    @Test
    void insertsDynamicMemoryImmediatelyBeforeCurrentUserMessageWithoutPersistingIt() {
        MemoryFileStore store = new MemoryFileStore(
                new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString()),
                16 * 1024, 200, 25 * 1024, 200
        );
        MemoryService service = new MemoryService(store);
        service.remember(new RememberMemoryCommand(
                "java-style",
                MemoryScope.PROJECT,
                MemoryType.FEEDBACK,
                MemoryActivation.RELEVANT,
                "Java 长字符串",
                "Java 长字符串使用文本块",
                "静态长字符串不要连续 append。",
                null
        ));
        ContextService builder = new ContextService(
                List.of(),
                Map.of(),
                new TestConfig(tempDir),
                new MemoryContextBuilder(service, store, new MemoryRecallService(store), 4_096, 5, 4_096, 20_480),
                new AutoCompactConfig(128_000, 4_096, true, true, null, true)
        );
        List<WorkingMessage> history = List.of(
                WorkingMessage.original(1, UserMessage.from("请修改 Java 长字符串实现"))
        );

        List<ChatMessage> requestMessages = builder.buildWorking(history, tempDir).messages();

        int currentUser = indexOfUserText(requestMessages, "请修改 Java 长字符串实现");
        int memoryContext = indexOfUserText(requestMessages, "<memory-context>");
        assertTrue(memoryContext >= 0);
        assertEquals(currentUser - 1, memoryContext);
        assertTrue(requestMessages.get(memoryContext) instanceof UserMessage);
        assertFalse(requestMessages.stream()
                .filter(SystemMessage.class::isInstance)
                .map(message -> ((SystemMessage) message).text())
                .anyMatch(text -> text.contains("静态长字符串不要连续 append")));
        assertEquals(1, history.size());
    }

    @Test
    void syntheticSummaryDoesNotReplaceTheLatestOriginalUserForMemoryRecall() {
        MemoryFileStore store = new MemoryFileStore(
                new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString()),
                16 * 1024, 200, 25 * 1024, 200
        );
        MemoryService service = new MemoryService(store);
        service.remember(new RememberMemoryCommand(
                "java-style",
                MemoryScope.PROJECT,
                MemoryType.FEEDBACK,
                MemoryActivation.RELEVANT,
                "Java 长字符串",
                "Java 长字符串使用文本块",
                "静态长字符串不要连续 append。",
                null
        ));
        ContextService builder = new ContextService(
                List.of(),
                Map.of(),
                new TestConfig(tempDir),
                new MemoryContextBuilder(service, store, new MemoryRecallService(store), 4_096, 5, 4_096, 20_480),
                new AutoCompactConfig(128_000, 4_096, true, true, null, true)
        );
        List<WorkingMessage> history = List.of(
                WorkingMessage.original(1, UserMessage.from("请修改 Java 长字符串实现")),
                WorkingMessage.synthetic(UserMessage.from("<conversation-summary>unrelated</conversation-summary>"))
        );

        List<ChatMessage> requestMessages = builder.buildWorking(history, tempDir).messages();

        int currentUser = indexOfUserText(requestMessages, "请修改 Java 长字符串实现");
        int memoryContext = indexOfUserText(requestMessages, "<memory-context>");
        assertEquals(currentUser - 1, memoryContext);
        assertTrue(requestMessages.stream().anyMatch(message -> message instanceof UserMessage user
                && user.singleText().contains("<conversation-summary>")));
    }

    private static int indexOfUserText(List<ChatMessage> messages, String text) {
        for (int index = 0; index < messages.size(); index++) {
            if (messages.get(index) instanceof UserMessage userMessage
                    && userMessage.singleText().contains(text)) {
                return index;
            }
        }
        return -1;
    }

    private static final class TestConfig extends AppConfig {
        private final Path workspace;

        private TestConfig(Path workspace) {
            super("__missing_context_builder_memory_test_config__.yaml");
            this.workspace = workspace;
        }

        @Override
        public String getWorkspace() {
            return workspace.toString();
        }
    }
}
