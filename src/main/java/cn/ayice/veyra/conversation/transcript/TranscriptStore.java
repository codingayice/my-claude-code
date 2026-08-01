package cn.ayice.veyra.conversation.transcript;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * append-only JSONL transcript 仓储。写入路径和读取格式都集中在这里，运行时不直接操作磁盘文件。
 */
public class TranscriptStore {

    private static final Logger log = LoggerFactory.getLogger(TranscriptStore.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SessionPathResolver pathResolver;

    /**
     * 使用会话路径解析器创建 JSONL 转录存储。
     */
    public TranscriptStore(SessionPathResolver pathResolver) {
        this.pathResolver = pathResolver;
    }

    /**
     * 返回指定会话的 transcript 文件路径。
     */
    public Path transcriptPath(String sessionId) {
        return pathResolver.transcriptPath(sessionId);
    }

    /**
     * 将一条转录记录原子追加到指定会话的 JSONL 文件。
     */
    public synchronized void append(String sessionId, TranscriptEntry entry) {
        // 同一 Store 实例内串行写入，防止多个异步事件的 JSON 行相互穿插。
        Path path = pathResolver.transcriptPath(sessionId);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, MAPPER.writeValueAsString(entry) + "\n",
                    StandardCharsets.UTF_8,
                    Files.exists(path)
                            ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (IOException e) {
            throw new IllegalStateException("写入会话 transcript 失败: " + sessionId, e);
        }
    }

    /**
     * 按文件顺序读取指定会话的全部转录记录。
     */
    public List<TranscriptEntry> read(String sessionId) {
        Path path = pathResolver.transcriptPath(sessionId);
        if (!Files.exists(path)) {
            return List.of();
        }
        return readFile(path);
    }

    /**
     * 扫描当前项目的 transcript 文件并按最近更新时间倒序返回会话摘要。
     */
    public List<SessionRecord> listSessions() {
        Path projectDir = pathResolver.projectDir();
        if (!Files.exists(projectDir)) {
            return List.of();
        }
        List<SessionRecord> records = new ArrayList<>();
        try (var stream = Files.list(projectDir)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".jsonl"))
                    .forEach(path -> toRecord(path).ifPresent(records::add));
        } catch (IOException e) {
            throw new IllegalStateException("读取会话列表失败: " + projectDir, e);
        }
        records.sort(Comparator.comparing(SessionRecord::updatedAt).reversed());
        return records;
    }

    /**
     * 使用首尾转录记录构建会话摘要；空文件不生成会话。
     */
    private java.util.Optional<SessionRecord> toRecord(Path path) {
        List<TranscriptEntry> entries = readFile(path);
        if (entries.isEmpty()) {
            return java.util.Optional.empty();
        }
        TranscriptEntry first = entries.get(0);
        TranscriptEntry last = entries.get(entries.size() - 1);
        String fileName = path.getFileName().toString();
        String sessionId = fileName.substring(0, fileName.length() - ".jsonl".length());
        return java.util.Optional.of(new SessionRecord(
                sessionId,
                first.content() == null ? sessionId : first.content(),
                safeInstant(first.timestamp()),
                safeInstant(last.timestamp()),
                path
        ));
    }

    /**
     * 将一个 JSONL 文件逐行反序列化为转录记录。
     */
    private List<TranscriptEntry> readFile(Path path) {
        try {
            List<TranscriptEntry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    entries.add(MAPPER.readValue(line, TranscriptEntry.class));
                }
            }
            return entries;
        } catch (IOException e) {
            throw new IllegalStateException("读取会话 transcript 失败: " + path, e);
        }
    }

    /**
     * 安全解析时间戳，无效历史数据降级为纪元时间并记录告警。
     */
    private static Instant safeInstant(String value) {
        try {
            return Instant.parse(value);
        } catch (Exception e) {
            log.warn("Transcript 时间戳无效: {}", value, e);
            return Instant.EPOCH;
        }
    }
}
