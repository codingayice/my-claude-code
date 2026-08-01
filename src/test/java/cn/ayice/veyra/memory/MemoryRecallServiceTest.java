package cn.ayice.veyra.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryRecallServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void recallsRelevantUserAndProjectTopicsWithDeterministicLimit() {
        MemoryFileStore store = store();
        MemoryService memory = new MemoryService(store);
        for (int index = 1; index <= 7; index++) {
            memory.remember(command(
                    "java-memory-" + index,
                    MemoryScope.PROJECT,
                    "Java 后端偏好 " + index,
                    "Java backend 长字符串规范 " + index
            ));
        }
        memory.remember(command(
                "java-user-feedback",
                MemoryScope.USER,
                "重构 Java backend 长字符串",
                "重构 Java backend 长字符串时需要给出验证结果"
        ));
        memory.remember(command(
                "document-style",
                MemoryScope.USER,
                "文档格式",
                "编写普通中文文档时使用短段落"
        ));

        MemoryRecallResult result = new MemoryRecallService(store).recall(new MemoryRecallQuery(
                "重构 Java backend 的长字符串实现",
                Set.of(),
                5,
                4_096,
                20_480
        ));

        assertEquals(5, result.memories().size());
        assertTrue(result.memories().stream().allMatch(item -> item.score() > 0));
        assertTrue(result.memories().stream().anyMatch(item -> item.entry().scope() == MemoryScope.USER));
        assertFalse(result.memories().stream().anyMatch(item -> item.entry().id().equals("document-style")));
    }

    @Test
    void emptyOrUnrelatedQueryDoesNotGuessTopics() {
        MemoryFileStore store = store();
        new MemoryService(store).remember(command(
                "java-style",
                MemoryScope.PROJECT,
                "Java 规范",
                "Java 长字符串使用文本块"
        ));
        MemoryRecallService recall = new MemoryRecallService(store);

        assertTrue(recall.recall(new MemoryRecallQuery("", Set.of(), 5, 4_096, 20_480)).memories().isEmpty());
        assertTrue(recall.recall(new MemoryRecallQuery("上下文窗口", Set.of(), 5, 4_096, 20_480)).memories().isEmpty());
    }

    private MemoryFileStore store() {
        return new MemoryFileStore(
                new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString()),
                16 * 1024,
                200,
                25 * 1024,
                200
        );
    }

    private static RememberMemoryCommand command(String id, MemoryScope scope, String name, String content) {
        return new RememberMemoryCommand(
                id,
                scope,
                MemoryType.FEEDBACK,
                MemoryActivation.RELEVANT,
                name,
                content,
                content,
                null
        );
    }
}
