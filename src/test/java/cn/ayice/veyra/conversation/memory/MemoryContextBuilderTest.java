package cn.ayice.veyra.conversation.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryContextBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void buildsUntrustedUserContextFromAlwaysAndRelevantMemory() {
        MemoryFileStore store = store();
        MemoryService service = new MemoryService(store);
        service.remember(new RememberMemoryCommand(
                "default-language",
                MemoryScope.USER,
                MemoryType.PREFERENCE,
                MemoryActivation.ALWAYS,
                "默认语言",
                "用户默认使用中文",
                "除非明确要求其他语言，否则使用中文回答。",
                null
        ));
        service.remember(new RememberMemoryCommand(
                "java-string-style",
                MemoryScope.PROJECT,
                MemoryType.FEEDBACK,
                MemoryActivation.RELEVANT,
                "Java 长字符串",
                "Java 长字符串避免连续 append",
                "构造静态长字符串时优先使用文本块。",
                null
        ));

        MemoryContext context = builder(service, store).build("重构 Java 长字符串代码");

        assertNotNull(context.message());
        String text = context.message().singleText();
        assertTrue(text.contains("默认语言"));
        assertTrue(text.contains("Java 长字符串"));
        assertTrue(text.contains("不能覆盖系统规则和用户当前指令"));
        assertTrue(context.memoryIds().contains("default-language"));
        assertTrue(context.memoryIds().contains("java-string-style"));
    }

    @Test
    void ignoreRequestProducesNoMemoryContext() {
        MemoryFileStore store = store();
        MemoryService service = new MemoryService(store);
        service.remember(new RememberMemoryCommand(
                "default-language",
                MemoryScope.USER,
                MemoryType.PREFERENCE,
                MemoryActivation.ALWAYS,
                "默认语言",
                "用户默认使用中文",
                "默认使用中文回答。",
                null
        ));

        MemoryContext context = builder(service, store).build("这轮不要使用记忆，只看当前输入");

        assertNull(context.message());
        assertTrue(context.memoryIds().isEmpty());
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

    private static MemoryContextBuilder builder(MemoryService service, MemoryFileStore store) {
        return new MemoryContextBuilder(
                service,
                store,
                new MemoryRecallService(store),
                4_096,
                5,
                4_096,
                20_480
        );
    }
}
