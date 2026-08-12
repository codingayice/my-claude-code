package cn.ayice.veyra.session.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import cn.ayice.veyra.session.RunCheckpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 崩溃安全的 append-only Session JSONL Journal。
 *
 * <p>单进程内由该 Store 串行分配 sequence。读取时只允许尾部损坏，
 * 中间行损坏会阻止恢复，避免静默跳过稳定事实。</p>
 */
public final class SessionJournalStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger log = LoggerFactory.getLogger(SessionJournalStore.class);

    private final SessionPathResolver pathResolver;
    private final SessionIndexProjector indexProjector;
    private final JsonSessionIndexStore indexStore;
    private final JsonRunSnapshotStore snapshotStore;
    private final Map<String, Long> nextSequences = new HashMap<>();

    public SessionJournalStore(SessionPathResolver pathResolver) {
        this.pathResolver = pathResolver;
        this.indexProjector = new SessionIndexProjector();
        this.indexStore = new JsonSessionIndexStore(pathResolver, indexProjector);
        this.snapshotStore = new JsonRunSnapshotStore(pathResolver);
    }

    /** 返回指定 Session 的 Journal 文件路径。 */
    public Path journalPath(String sessionId) {
        return pathResolver.journalPath(sessionId);
    }

    /** 分配下一个 sequence，追加完整 JSON 行并按要求强制刷盘。 */
    public synchronized SessionJournalEntry append(
            String sessionId,
            String runId,
            String type,
            Map<String, Object> payload,
            boolean durable
    ) {
        return append(sessionId, runId, type, payload, durable, null, UUID.randomUUID().toString());
    }

    /** 使用 expectedRevision 和稳定 eventId 原子追加；重复 eventId 幂等返回原事件。 */
    public synchronized SessionJournalEntry append(
            String sessionId,
            String runId,
            String type,
            Map<String, Object> payload,
            boolean durable,
            Long expectedRevision,
            String eventId
    ) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        Long knownNext = nextSequences.get(sessionId);
        List<SessionJournalEntry> existing = Files.exists(journalPath(sessionId))
                ? readAndRepair(journalPath(sessionId))
                : List.of();
        Optional<SessionJournalEntry> duplicate = existing.stream()
                .filter(entry -> eventId.equals(entry.eventId()))
                .findFirst();
        if (duplicate.isPresent()) {
            SessionJournalEntry event = duplicate.get();
            if (!java.util.Objects.equals(event.runId(), blankToNull(runId))
                    || !event.type().equals(type)
                    || !event.payload().equals(payload == null ? Map.of() : payload)) {
                throw new IllegalStateException("EVENT_ID_CONFLICT");
            }
            return event;
        }
        long currentRevision = existing.isEmpty() ? 0L : existing.get(existing.size() - 1).sequence();
        if (expectedRevision != null && expectedRevision != currentRevision) {
            throw new IllegalStateException("SESSION_REVISION_CONFLICT");
        }
        long sequence;
        if (knownNext == null) {
            sequence = existing.isEmpty() ? 1L : existing.get(existing.size() - 1).sequence() + 1;
        } else {
            sequence = knownNext;
        }
        SessionJournalEntry entry = new SessionJournalEntry(
                1,
                eventId,
                sequence,
                sessionId,
                blankToNull(runId),
                type,
                System.currentTimeMillis(),
                payload
        );
        Path path = journalPath(sessionId);
        try {
            Files.createDirectories(path.getParent());
            byte[] bytes = (MAPPER.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    path,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            )) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                if (durable) {
                    channel.force(false);
                }
            }
            nextSequences.put(sessionId, sequence + 1);
            updateDerivedState(entry);
            return entry;
        } catch (IOException failure) {
            throw new IllegalStateException("写入 Session Journal 失败: " + sessionId, failure);
        }
    }

    /** 读取并修复指定 Session 的有效 Journal。 */
    public synchronized List<SessionJournalEntry> read(String sessionId) {
        Path path = journalPath(sessionId);
        if (!Files.exists(path)) {
            return List.of();
        }
        List<SessionJournalEntry> entries = readAndRepair(path);
        long next = entries.isEmpty() ? 1L : entries.get(entries.size() - 1).sequence() + 1;
        nextSequences.put(sessionId, next);
        return entries;
    }

    /** 扫描当前工作区 Journal 并生成按更新时间倒序的会话摘要。 */
    public synchronized List<SessionRecord> listSessions() {
        Path projectDir = pathResolver.projectDir();
        if (!Files.exists(projectDir)) {
            return List.of();
        }
        List<SessionRecord> result = new ArrayList<>();
        try (var paths = Files.list(projectDir)) {
            paths.filter(Files::isDirectory)
                    .map(path -> path.resolve("events.jsonl"))
                    .filter(Files::exists)
                    .forEach(path -> toRecord(path).ifPresent(result::add));
        } catch (IOException failure) {
            throw new IllegalStateException("读取 Session Journal 列表失败: " + projectDir, failure);
        }
        result.sort(Comparator.comparing(SessionRecord::updatedAt).reversed());
        return List.copyOf(result);
    }

    /** 删除指定 Session 的完整持久化目录；不存在时返回 false。 */
    public synchronized boolean delete(String sessionId) {
        Path path = pathResolver.sessionDir(sessionId).toAbsolutePath().normalize();
        Path project = pathResolver.projectDir().toAbsolutePath().normalize();
        if (!path.startsWith(project) || path.equals(project)) {
            throw new IllegalArgumentException("refusing to delete path outside project directory");
        }
        try {
            if (!Files.exists(path)) {
                return false;
            }
            try (var files = Files.walk(path)) {
                for (Path target : files.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(target);
                }
            }
            nextSequences.remove(sessionId);
            return true;
        } catch (IOException failure) {
            throw new IllegalStateException("删除 Session Journal 失败: " + sessionId, failure);
        }
    }

    /** 解析完整前缀并物理修复唯一允许损坏的文件尾部。 */
    private List<SessionJournalEntry> readAndRepair(Path path) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) {
                return List.of();
            }
            List<SessionJournalEntry> entries = new ArrayList<>();
            int lineStart = 0;
            for (int index = 0; index < bytes.length; index++) {
                if (bytes[index] != '\n') {
                    continue;
                }
                parseCompleteLine(path, bytes, lineStart, index, entries);
                lineStart = index + 1;
            }
            if (lineStart < bytes.length) {
                String tail = new String(bytes, lineStart, bytes.length - lineStart, StandardCharsets.UTF_8).trim();
                if (!tail.isEmpty()) {
                    try {
                        entries.add(MAPPER.readValue(tail, SessionJournalEntry.class));
                        appendMissingNewline(path);
                    } catch (IOException malformedTail) {
                        truncate(path, lineStart);
                    }
                }
            }
            validateSequence(path, entries);
            return List.copyOf(entries);
        } catch (IOException failure) {
            throw new IllegalStateException("读取 Session Journal 失败: " + path, failure);
        }
    }

    /** 解析一个以换行结束的完整 Journal 行。 */
    private static void parseCompleteLine(
            Path path,
            byte[] bytes,
            int start,
            int end,
            List<SessionJournalEntry> entries
    ) throws IOException {
        String line = new String(bytes, start, end - start, StandardCharsets.UTF_8).trim();
        if (line.isEmpty()) {
            return;
        }
        try {
            entries.add(MAPPER.readValue(line, SessionJournalEntry.class));
        } catch (IOException malformedMiddleLine) {
            throw new IllegalStateException("Session Journal 中间行损坏: " + path, malformedMiddleLine);
        }
    }

    /** 验证 Session 内业务序号从一开始严格连续。 */
    private static void validateSequence(Path path, List<SessionJournalEntry> entries) {
        long expected = 1L;
        for (SessionJournalEntry entry : entries) {
            if (entry.sequence() != expected) {
                throw new IllegalStateException(
                        "Session Journal sequence 不连续: " + path + ", expected=" + expected
                                + ", actual=" + entry.sequence()
                );
            }
            expected++;
        }
    }

    /** 为完整但缺少终止换行的最后一条记录补换行并刷盘。 */
    private static void appendMissingNewline(Path path) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            channel.write(ByteBuffer.wrap(new byte[]{'\n'}));
            channel.force(false);
        }
    }

    /** 截断尾部半行并同步元数据前的文件内容。 */
    private static void truncate(Path path, long size) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(size);
            channel.force(false);
        }
    }

    /** 将非空 Journal 投影为会话列表摘要。 */
    private java.util.Optional<SessionRecord> toRecord(Path path) {
        List<SessionJournalEntry> entries = readAndRepair(path);
        if (entries.isEmpty()) {
            return java.util.Optional.empty();
        }
        String sessionId = path.getParent().getFileName().toString();
        String title = entries.stream()
                .filter(entry -> SessionJournalTypes.USER_MESSAGE_RECORDED.equals(entry.type()))
                .map(entry -> String.valueOf(entry.payload().getOrDefault("text", sessionId)))
                .findFirst()
                .orElse(sessionId);
        return java.util.Optional.of(new SessionRecord(
                sessionId,
                title,
                Instant.ofEpochMilli(entries.get(0).timestampMs()),
                Instant.ofEpochMilli(entries.get(entries.size() - 1).timestampMs()),
                path
        ));
    }

    /** 把空标识规范化为空值，避免磁盘出现无意义空字符串。 */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** 返回由事实流校验和修复后的 SessionIndex。 */
    public synchronized SessionIndex index(String sessionId) {
        return indexStore.loadOrRebuild(sessionId, read(sessionId));
    }

    /** 返回一个有效终态 Snapshot；缺失或损坏时从事件重建并补写。 */
    public synchronized RunSnapshot snapshot(String sessionId, String runId) {
        SessionIndex index = index(sessionId);
        RunIndexEntry run = index.runs().get(runId);
        if (run == null || !run.terminal()) {
            throw new IllegalArgumentException("run is not a terminal checkpoint: " + runId);
        }
        Optional<RunSnapshot> stored = snapshotStore.read(sessionId, runId)
                .filter(snapshot -> snapshot.terminalRevision() == run.terminalRevision());
        if (stored.isPresent()) {
            return stored.get();
        }
        RunSnapshot rebuilt = buildSnapshot(index, run, read(sessionId));
        snapshotStore.write(rebuilt);
        indexStore.write(indexProjector.markSnapshotAvailable(index, runId));
        return rebuilt;
    }

    /** 返回当前 Run 路径上的事件，Session 级设置事件始终取最新值。 */
    public synchronized List<SessionJournalEntry> currentPathEvents(String sessionId) {
        List<SessionJournalEntry> all = read(sessionId);
        SessionIndex index = indexStore.loadOrRebuild(sessionId, all);
        String targetRunId = index.activeRunId() != null ? index.activeRunId() : index.currentRunId();
        return pathEvents(all, index, targetRunId);
    }

    /** 返回当前路径事件；Runtime 的 AgentState 恢复由 recoveryAgentState 走 Snapshot 热路径。 */
    public synchronized List<SessionJournalEntry> recoveryEvents(String sessionId) {
        List<SessionJournalEntry> all = read(sessionId);
        SessionIndex index = indexStore.loadOrRebuild(sessionId, all);
        if (index.activeRunId() != null || index.currentRunId() == null) {
            return pathEvents(all, index, index.activeRunId());
        }
        return pathEvents(all, index, index.currentRunId());
    }

    /** 返回指定终态 Run 的 Snapshot 恢复基线，Session 设置始终使用事件流中的最新值。 */
    public synchronized List<SessionJournalEntry> recoveryEventsAt(String sessionId, String runId) {
        List<SessionJournalEntry> all = read(sessionId);
        SessionIndex index = indexStore.loadOrRebuild(sessionId, all);
        return pathEvents(all, index, runId);
    }

    /** 使用结构化 Snapshot 返回当前终态路径 AgentState。 */
    public synchronized cn.ayice.veyra.session.state.AgentState recoveryAgentState(String sessionId) {
        SessionIndex index = index(sessionId);
        if (index.activeRunId() == null && index.currentRunId() != null) {
            return snapshot(sessionId, index.currentRunId()).agentState();
        }
        return new cn.ayice.veyra.session.state.SessionProjection().reduceAgent(currentPathEvents(sessionId));
    }

    /** 使用指定终态 RunSnapshot 返回结构化 AgentState。 */
    public synchronized cn.ayice.veyra.session.state.AgentState recoveryAgentStateAt(String sessionId, String runId) {
        return snapshot(sessionId, runId).agentState();
    }

    /** 返回指定终态 Run 路径上的事件，用于从历史检查点创建新的子 Run。 */
    public synchronized List<SessionJournalEntry> pathEvents(String sessionId, String runId) {
        List<SessionJournalEntry> all = read(sessionId);
        SessionIndex index = indexStore.loadOrRebuild(sessionId, all);
        RunIndexEntry run = index.runs().get(runId);
        if (run == null || !run.terminal()) {
            throw new IllegalArgumentException("run is not a terminal checkpoint: " + runId);
        }
        return pathEvents(all, index, runId);
    }

    /** 从给定事件集合筛选目标 Run 的根到节点路径。 */
    private List<SessionJournalEntry> pathEvents(
            List<SessionJournalEntry> all,
            SessionIndex index,
            String targetRunId
    ) {
        if (targetRunId == null) {
            return all;
        }
        Set<String> visibleRuns = Set.copyOf(indexProjector.pathTo(index, targetRunId));
        return all.stream()
                .filter(event -> event.runId() == null || visibleRuns.contains(event.runId()))
                .filter(event -> !SessionJournalTypes.CHECKPOINT_RESTORED.equals(event.type()))
                .toList();
    }

    /** 持久化用户当前选择的终态 Run；Snapshot 只负责提供该 Run 的状态。 */
    public synchronized SessionIndex restoreCheckpoint(String sessionId, String runId, long expectedRevision) {
        List<SessionJournalEntry> events = read(sessionId);
        SessionIndex index = indexStore.loadOrRebuild(sessionId, events);
        if (index.appliedRevision() != expectedRevision) {
            throw new IllegalStateException("SESSION_REVISION_CONFLICT");
        }
        if (index.activeRunId() != null) {
            throw new IllegalStateException("SESSION_ALREADY_RUNNING");
        }
        RunIndexEntry target = index.runs().get(runId);
        if (target == null || !target.terminal()) {
            throw new IllegalArgumentException("run is not a terminal checkpoint: " + runId);
        }
        snapshot(sessionId, runId);
        append(sessionId, null, SessionJournalTypes.CHECKPOINT_RESTORED, Map.of(
                "previousRunId", index.currentRunId() == null ? "" : index.currentRunId(),
                "checkpointRunId", runId,
                "reason", "user_restore"
        ), true);
        return index(sessionId);
    }

    /** 返回按终态 revision 排序的 Run 检查点投影。 */
    public synchronized List<RunCheckpoint> checkpoints(String sessionId) {
        SessionIndex index = index(sessionId);
        return index.runs().values().stream()
                .filter(RunIndexEntry::terminal)
                .sorted(Comparator.comparingLong(run -> run.terminalRevision()))
                .map(run -> new RunCheckpoint(
                        run.runId(),
                        run.parentRunId(),
                        run.terminalRevision(),
                        run.status(),
                        run.runId().equals(index.currentRunId()),
                        run.snapshotAvailable() || snapshotStore.read(sessionId, run.runId()).isPresent()
                ))
                .toList();
    }

    /** 事件已成为事实后尽力同步可重建 Index 和终态 Snapshot。 */
    private void updateDerivedState(SessionJournalEntry entry) {
        try {
            List<SessionJournalEntry> events = readAndRepair(journalPath(entry.sessionId()));
            SessionIndex index = indexStore.loadOrRebuild(entry.sessionId(), events);
            if (SessionJournalTypes.RUN_TERMINALS.contains(entry.type())) {
                RunIndexEntry run = index.runs().get(entry.runId());
                RunSnapshot snapshot = buildSnapshot(index, run, events);
                snapshotStore.write(snapshot);
                indexStore.write(indexProjector.markSnapshotAvailable(index, entry.runId()));
            }
        } catch (RuntimeException derivedFailure) {
            log.warn("Session 派生状态写入失败，将在下次访问时重建: session={}, revision={}",
                    entry.sessionId(), entry.sequence(), derivedFailure);
        }
    }

    /** 从事实流构建一个终态 Run 的可重建 Snapshot。 */
    private RunSnapshot buildSnapshot(
            SessionIndex index,
            RunIndexEntry run,
            List<SessionJournalEntry> events
    ) {
        Set<String> visibleRuns = Set.copyOf(indexProjector.pathTo(index, run.runId()));
        List<SessionJournalEntry> pathEvents = events.stream()
                .filter(event -> event.sequence() <= run.terminalRevision())
                .filter(event -> event.runId() == null || visibleRuns.contains(event.runId()))
                .filter(event -> !SessionJournalTypes.CHECKPOINT_RESTORED.equals(event.type()))
                .toList();
        return snapshotStore.create(index.sessionId(), run, pathEvents);
    }
}
