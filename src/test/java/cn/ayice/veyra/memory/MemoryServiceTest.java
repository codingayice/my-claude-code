package cn.ayice.veyra.memory;

import cn.ayice.veyra.memory.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void rememberAndForgetReturnExplicitPersistenceResults() {
        MemoryService service = service();
        MemoryService.Operation saved = service.remember(new MemoryService.Remember(
                null,
                MemoryEntry.Scope.USER,
                MemoryEntry.Type.PREFERENCE,
                MemoryEntry.Activation.ALWAYS,
                "默认回答语言",
                "用户希望 Veyra 默认使用中文回答",
                "除非用户明确要求其他语言，否则默认使用中文回答。",
                "session-1"
        ));

        assertTrue(saved.success());
        assertNotNull(saved.entry());
        assertEquals(1, service.list(MemoryEntry.Scope.USER).size());

        MemoryService.Operation forgotten = service.forget(new MemoryService.Forget(
                MemoryEntry.Scope.USER,
                saved.entry().id()
        ));
        assertTrue(forgotten.success());
        assertTrue(service.list(MemoryEntry.Scope.USER).isEmpty());
    }

    @Test
    void alwaysActivationIsRestrictedToUserPreference() {
        MemoryService.Operation result = service().remember(new MemoryService.Remember(
                "project-always",
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Type.CONTEXT,
                MemoryEntry.Activation.ALWAYS,
                "项目规则",
                "项目长期规则",
                "工具调用必须等待整批完成。",
                null
        ));

        assertFalse(result.success());
        assertEquals(MemoryException.Code.MEMORY_INVALID_REQUEST, result.errorCode());
    }

    @Test
    void sensitiveContentIsRejectedWithoutCreatingTopic() {
        MemoryService service = service();
        MemoryService.Operation result = service.remember(new MemoryService.Remember(
                "secret",
                MemoryEntry.Scope.USER,
                MemoryEntry.Type.REFERENCE,
                MemoryEntry.Activation.RELEVANT,
                "服务凭据",
                "外部服务入口",
                "api_key=<redacted>",
                null
        ));

        assertFalse(result.success());
        assertEquals(MemoryException.Code.MEMORY_SENSITIVE_CONTENT, result.errorCode());
        assertTrue(service.list(MemoryEntry.Scope.USER).isEmpty());
    }

    @Test
    void indexFailureReturnsSavedEntryAsPartialSuccess() throws Exception {
        MemoryService service = service();
        Path index = service.paths().index(MemoryEntry.Scope.PROJECT);
        Files.delete(index);
        Files.createDirectory(index);
        Files.writeString(index.resolve("block-replacement"), "non-empty");

        MemoryService.Operation result = service.remember(new MemoryService.Remember(
                "tool-barrier",
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Type.CONTEXT,
                MemoryEntry.Activation.RELEVANT,
                "工具批次屏障",
                "同一轮工具全部完成后才能继续",
                "工具可以并行执行，但必须等待本轮全部工具结束后再调用模型。",
                null
        ));

        assertFalse(result.success());
        assertTrue(result.partial());
        assertEquals(MemoryException.Code.MEMORY_INDEX_REBUILD_FAILED, result.errorCode());
        assertNotNull(result.entry());
        assertTrue(Files.exists(service.paths().topic(MemoryEntry.Scope.PROJECT, "tool-barrier")));
    }

    @Test
    void disabledMemoryRejectsExplicitWritesWithoutChangingTopics() {
        MemoryService service = service();
        service.setEnabled(false);

        MemoryService.Operation result = service.remember(new MemoryService.Remember(
                "disabled-write",
                MemoryEntry.Scope.USER,
                MemoryEntry.Type.PREFERENCE,
                MemoryEntry.Activation.RELEVANT,
                "关闭后的写入",
                "关闭长期记忆时不得继续写入",
                "这条内容不应被保存。",
                null
        ));

        assertFalse(result.success());
        assertEquals(MemoryException.Code.MEMORY_INVALID_REQUEST, result.errorCode());
        assertFalse(Files.exists(service.paths().topic(MemoryEntry.Scope.USER, "disabled-write")));
    }

    @Test
    void forgetReportsPartialSuccessWhenTopicWasDeletedButIndexRebuildFailed() throws Exception {
        MemoryService service = service();
        MemoryService.Operation saved = service.remember(new MemoryService.Remember(
                "obsolete-context",
                MemoryEntry.Scope.PROJECT,
                MemoryEntry.Type.CONTEXT,
                MemoryEntry.Activation.RELEVANT,
                "过期项目背景",
                "准备删除的项目背景",
                "该内容已经过期。",
                null
        ));
        Path index = service.paths().index(MemoryEntry.Scope.PROJECT);
        Files.delete(index);
        Files.createDirectory(index);
        Files.writeString(index.resolve("block-replacement"), "non-empty");

        MemoryService.Operation result = service.forget(new MemoryService.Forget(
                MemoryEntry.Scope.PROJECT,
                saved.entry().id()
        ));

        assertFalse(result.success());
        assertTrue(result.partial());
        assertEquals(MemoryException.Code.MEMORY_INDEX_REBUILD_FAILED, result.errorCode());
        assertFalse(Files.exists(service.paths().topic(MemoryEntry.Scope.PROJECT, saved.entry().id())));
    }

    private MemoryService service() {
        MemoryPaths paths = new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString());
        return new MemoryService(new MemoryFileStore(paths, 16 * 1024, 200, 25 * 1024, 200), 4_096, 5, 4_096, 20_480);
    }
}
