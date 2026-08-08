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

/**
 * 崩溃安全的 append-only Session JSONL Journal。
 *
 * <p>单进程内由该 Store 串行分配 sequence。读取时只允许尾部损坏，
 * 中间行损坏会阻止恢复，避免静默跳过稳定事实。</p>
 */
public final class SessionJournalStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionPathResolver pathResolver;
    private final Map<String, Long> nextSequences = new HashMap<>();

    public SessionJournalStore(SessionPathResolver pathResolver) {
        this.pathResolver = pathResolver;
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
        Long knownNext = nextSequences.get(sessionId);
        long sequence;
        if (knownNext == null) {
            List<SessionJournalEntry> existing = Files.exists(journalPath(sessionId))
                    ? readAndRepair(journalPath(sessionId))
                    : List.of();
            sequence = existing.isEmpty() ? 1L : existing.get(existing.size() - 1).sequence() + 1;
        } else {
            sequence = knownNext;
        }
        SessionJournalEntry entry = new SessionJournalEntry(
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
            paths.filter(path -> path.getFileName().toString().endsWith(".journal.jsonl"))
                    .forEach(path -> toRecord(path).ifPresent(result::add));
        } catch (IOException failure) {
            throw new IllegalStateException("读取 Session Journal 列表失败: " + projectDir, failure);
        }
        result.sort(Comparator.comparing(SessionRecord::updatedAt).reversed());
        return List.copyOf(result);
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
        String fileName = path.getFileName().toString();
        String suffix = ".journal.jsonl";
        String sessionId = fileName.substring(0, fileName.length() - suffix.length());
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
}
