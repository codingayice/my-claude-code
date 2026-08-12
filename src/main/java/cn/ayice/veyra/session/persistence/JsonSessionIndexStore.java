package cn.ayice.veyra.session.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Optional;

/** 原子持久化并按事件流 revision 校验可重建 SessionIndex。 */
public final class JsonSessionIndexStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionPathResolver paths;
    private final SessionIndexProjector projector;

    public JsonSessionIndexStore(SessionPathResolver paths, SessionIndexProjector projector) {
        this.paths = paths;
        this.projector = projector;
    }

    /** 加载可用 Index，或从事件尾部修复/完整重建并持久化。 */
    public synchronized SessionIndex loadOrRebuild(String sessionId, List<SessionJournalEntry> events) {
        long head = events.isEmpty() ? 0L : events.get(events.size() - 1).sequence();
        Optional<SessionIndex> stored = read(sessionId);
        if (stored.isPresent()) {
            SessionIndex index = stored.get();
            if (index.appliedRevision() == head) {
                projector.validateGraph(index);
                return index;
            }
            if (index.appliedRevision() >= 0 && index.appliedRevision() < head) {
                try {
                    SessionIndex repaired = index;
                    for (SessionJournalEntry event : events) {
                        if (event.sequence() > repaired.appliedRevision()) {
                            repaired = projector.apply(repaired, event);
                        }
                    }
                    projector.validateGraph(repaired);
                    write(repaired);
                    return repaired;
                } catch (RuntimeException ignored) {
                    // 完整重建是权威降级路径。
                }
            }
        }
        SessionIndex rebuilt = projector.project(sessionId, events);
        write(rebuilt);
        return rebuilt;
    }

    /** 读取并结构校验已持久化 Index，无效时返回空。 */
    public synchronized Optional<SessionIndex> read(String sessionId) {
        Path path = paths.sessionIndexPath(sessionId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            SessionIndex index = MAPPER.readValue(path.toFile(), SessionIndex.class);
            if (!sessionId.equals(index.sessionId())) {
                return Optional.empty();
            }
            projector.validateGraph(index);
            return Optional.of(index);
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    /** 原子替换指定 Session 的 Index 文件。 */
    public synchronized void write(SessionIndex index) {
        Path target = paths.sessionIndexPath(index.sessionId());
        atomicWrite(target, index);
    }

    /** 使用临时文件、force 和原子重命名写入一个 JSON 对象。 */
    static void atomicWrite(Path target, Object value) {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        try {
            Files.createDirectories(target.getParent());
            byte[] bytes = MAPPER.writeValueAsBytes(value);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            )) {
                channel.write(java.nio.ByteBuffer.wrap(bytes));
                channel.force(false);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("写入 SessionIndex 失败: " + target, failure);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // 临时文件由下次写入覆盖。
            }
        }
    }
}
