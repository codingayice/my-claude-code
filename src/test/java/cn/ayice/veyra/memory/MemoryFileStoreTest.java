package cn.ayice.veyra.memory;

import cn.ayice.veyra.memory.MemoryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryFileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void topicIsSourceOfTruthAndIndexCanBeRebuilt() throws Exception {
        MemoryFileStore store = store();
        MemoryEntry entry = entry("project-background", MemoryEntry.Scope.PROJECT, "项目背景");

        store.write(entry);

        Path topic = store.paths().topic(MemoryEntry.Scope.PROJECT, entry.id());
        String topicText = Files.readString(topic);
        String indexText = Files.readString(store.paths().index(MemoryEntry.Scope.PROJECT));
        assertTrue(topicText.contains("scope: project"));
        assertTrue(topicText.contains("activation: relevant"));
        assertTrue(indexText.contains("[项目背景](topics/project-background.md)"));
        assertFalse(indexText.contains(entry.content()));

        Files.writeString(store.paths().index(MemoryEntry.Scope.PROJECT), "broken");
        store.rebuildIndex(MemoryEntry.Scope.PROJECT);

        assertEquals(entry, store.read(MemoryEntry.Scope.PROJECT, entry.id()).orElseThrow());
        assertTrue(store.readIndex(MemoryEntry.Scope.PROJECT).contains("project-background.md"));
    }

    @Test
    void deleteRemovesTopicAndDerivedIndexEntry() {
        MemoryFileStore store = store();
        MemoryEntry entry = entry("testing-feedback", MemoryEntry.Scope.USER, "测试反馈");
        store.write(entry);

        assertTrue(store.delete(MemoryEntry.Scope.USER, entry.id()));

        assertTrue(store.read(MemoryEntry.Scope.USER, entry.id()).isEmpty());
        assertFalse(store.readIndex(MemoryEntry.Scope.USER).contains(entry.id()));
    }

    @Test
    void concurrentWritesInSameNamespaceDoNotLoseEntries() throws Exception {
        MemoryFileStore store = store();
        var executor = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Void>> writes = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                int current = index;
                writes.add(() -> {
                    store.write(entry("memory-" + current, MemoryEntry.Scope.PROJECT, "记忆 " + current));
                    return null;
                });
            }
            executor.invokeAll(writes).forEach(future -> {
                try {
                    future.get();
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        } finally {
            executor.shutdownNow();
        }

        assertEquals(20, store.list(MemoryEntry.Scope.PROJECT).size());
        assertEquals(20, store.readIndex(MemoryEntry.Scope.PROJECT).lines().filter(line -> line.startsWith("- [")).count());
    }

    @Test
    void initializationFailsWhenConfiguredRootCannotContainMemoryDirectories() throws Exception {
        Path regularFile = tempDir.resolve("not-a-directory");
        Files.writeString(regularFile, "occupied");
        MemoryPaths paths = new MemoryPaths(regularFile.toString(), tempDir.toString());

        MemoryException error = assertThrows(
                MemoryException.class,
                () -> new MemoryFileStore(paths, 16 * 1024, 200, 25 * 1024, 200)
        );

        assertEquals(MemoryException.Code.MEMORY_WRITE_FAILED, error.code());
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

    private static MemoryEntry entry(String id, MemoryEntry.Scope scope, String name) {
        Instant now = Instant.parse("2026-07-26T10:00:00Z");
        return new MemoryEntry(
                id,
                scope,
                MemoryEntry.Type.CONTEXT,
                MemoryEntry.Activation.RELEVANT,
                name,
                name + "的长期说明",
                name + "的长期正文",
                now,
                now,
                "session-test"
        );
    }
}
