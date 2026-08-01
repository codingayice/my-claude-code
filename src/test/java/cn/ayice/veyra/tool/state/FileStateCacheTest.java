package cn.ayice.veyra.tool.state;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileStateCacheTest {

    @Test
    void repeatedModificationMovesPathToTheMostRecentPosition() {
        FileStateCache cache = new FileStateCache();
        Path first = Path.of("first.txt").toAbsolutePath().normalize();
        Path second = Path.of("second.txt").toAbsolutePath().normalize();

        cache.recordModified(first);
        cache.recordModified(second);
        cache.recordModified(first);

        assertEquals(List.of(first, second), cache.recentModifiedPaths(5));
    }

    @Test
    void cacheStateAndModifiedPathsRemainConsistentUnderConcurrentAccess() {
        FileStateCache cache = new FileStateCache();
        CompletableFuture<?>[] tasks = IntStream.range(0, 50)
                .mapToObj(index -> CompletableFuture.runAsync(() -> {
                    Path path = Path.of("file-" + index + ".txt");
                    cache.set(path, FileStateCache.FileState.fromWrite("content " + index, index));
                    cache.recordModified(path);
                    cache.get(path);
                    cache.recentModifiedPaths(5);
                }))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(tasks).join();

        assertEquals(50, cache.size());
        assertEquals(5, cache.recentModifiedPaths(5).size());
    }
}
