package cn.ayice.veyra.conversation.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryPathsTest {

    @TempDir
    Path tempDir;

    @Test
    void projectKeyCombinesReadableNameAndCanonicalPathHash() throws Exception {
        Path first = Files.createDirectories(tempDir.resolve("first/workspace"));
        Path second = Files.createDirectories(tempDir.resolve("second/workspace"));

        MemoryPaths firstPaths = new MemoryPaths(tempDir.resolve("memory").toString(), first.toString());
        MemoryPaths secondPaths = new MemoryPaths(tempDir.resolve("memory").toString(), second.toString());

        assertTrue(firstPaths.projectKey().startsWith("workspace-"));
        assertNotEquals(firstPaths.projectKey(), secondPaths.projectKey());
        assertNotEquals(firstPaths.namespace(MemoryScope.PROJECT), secondPaths.namespace(MemoryScope.PROJECT));
    }

    @Test
    void topicRejectsTraversalAndAbsoluteIdentifiers() {
        MemoryPaths paths = new MemoryPaths(tempDir.resolve("memory").toString(), tempDir.toString());

        assertThrows(MemoryException.class, () -> paths.topic(MemoryScope.USER, "../secret"));
        assertThrows(MemoryException.class, () -> paths.topic(MemoryScope.USER, "C:\\secret"));
        assertThrows(MemoryException.class, () -> paths.topic(MemoryScope.USER, "topic/name"));
    }
}
