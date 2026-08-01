package cn.ayice.veyra.conversation.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 长期记忆的文件存储。topic 是事实来源，MEMORY.md 仅由本类扫描 topic 后生成。
 */
public final class MemoryFileStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryFileStore.class);
    private static final String FRONTMATTER_BOUNDARY = "---";

    private final MemoryPaths paths;
    private final int maxTopicBytes;
    private final int maxIndexLines;
    private final int maxIndexBytes;
    private final int maxScannedTopics;
    private final Map<Path, ReentrantReadWriteLock> locks = new ConcurrentHashMap<>();

    /**
     * 使用路径和硬预算创建文件存储。
     */
    public MemoryFileStore(
            MemoryPaths paths,
            int maxTopicBytes,
            int maxIndexLines,
            int maxIndexBytes,
            int maxScannedTopics
    ) {
        this.paths = paths;
        this.maxTopicBytes = positive(maxTopicBytes, "maxTopicBytes");
        this.maxIndexLines = positive(maxIndexLines, "maxIndexLines");
        this.maxIndexBytes = positive(maxIndexBytes, "maxIndexBytes");
        this.maxScannedTopics = positive(maxScannedTopics, "maxScannedTopics");
        initialize();
    }

    /**
     * 返回当前存储使用的路径计算器。
     */
    public MemoryPaths paths() {
        return paths;
    }

    /**
     * 读取指定作用域中的全部合法记忆，按激活方式和更新时间稳定排序。
     */
    public List<MemoryEntry> list(MemoryScope scope) {
        ReentrantReadWriteLock.ReadLock lock = lock(scope).readLock();
        lock.lock();
        try {
            return scan(scope);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 按稳定 id 读取一条记忆。
     */
    public Optional<MemoryEntry> read(MemoryScope scope, String id) {
        ReentrantReadWriteLock.ReadLock lock = lock(scope).readLock();
        lock.lock();
        try {
            Path topic = paths.topic(scope, id);
            return Files.exists(topic) ? Optional.of(readTopic(topic, scope)) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 原子创建或更新 topic，并在同一命名空间写锁内重建派生索引。
     */
    public void write(MemoryEntry entry) {
        ReentrantReadWriteLock.WriteLock lock = lock(entry.scope()).writeLock();
        lock.lock();
        try {
            Files.createDirectories(paths.topics(entry.scope()));
            String serialized = serialize(entry);
            if (serialized.getBytes(StandardCharsets.UTF_8).length > maxTopicBytes) {
                throw new MemoryException(MemoryErrorCode.MEMORY_BUDGET_EXCEEDED, "记忆正文超过持久化预算");
            }
            atomicWrite(paths.topic(entry.scope(), entry.id()), serialized);
            try {
                rebuildIndexUnderLock(entry.scope());
            } catch (MemoryException indexError) {
                throw new MemoryException(
                        MemoryErrorCode.MEMORY_INDEX_REBUILD_FAILED,
                        "记忆已保存，但索引重建失败",
                        indexError
                );
            }
        } catch (MemoryException error) {
            throw error;
        } catch (IOException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_WRITE_FAILED, "写入长期记忆失败", error);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 删除 topic 并重建索引，未命中时返回 false。
     */
    public boolean delete(MemoryScope scope, String id) {
        ReentrantReadWriteLock.WriteLock lock = lock(scope).writeLock();
        lock.lock();
        try {
            boolean deleted = Files.deleteIfExists(paths.topic(scope, id));
            if (deleted) {
                rebuildIndexUnderLock(scope);
            }
            return deleted;
        } catch (MemoryException error) {
            throw error;
        } catch (IOException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_WRITE_FAILED, "删除长期记忆失败", error);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 从全部合法 topic 重新生成指定作用域的索引。
     */
    public void rebuildIndex(MemoryScope scope) {
        ReentrantReadWriteLock.WriteLock lock = lock(scope).writeLock();
        lock.lock();
        try {
            rebuildIndexUnderLock(scope);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 返回指定作用域当前派生索引；索引缺失时先从 topic 重建。
     */
    public String readIndex(MemoryScope scope) {
        ReentrantReadWriteLock.WriteLock lock = lock(scope).writeLock();
        lock.lock();
        try {
            if (!Files.exists(paths.index(scope))) {
                rebuildIndexUnderLock(scope);
            }
            return Files.readString(paths.index(scope), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "读取长期记忆索引失败", error);
        } finally {
            lock.unlock();
        }
    }

    /**
     * 初始化用户级和项目级目录，并从 topic 修复派生索引。
     */
    private void initialize() {
        for (MemoryScope scope : MemoryScope.values()) {
            try {
                Files.createDirectories(paths.topics(scope));
                rebuildIndex(scope);
            } catch (IOException error) {
                throw new MemoryException(
                        MemoryErrorCode.MEMORY_WRITE_FAILED,
                        "创建长期记忆目录失败: " + scope,
                        error
                );
            }
        }
    }

    /**
     * 在调用方持有命名空间锁时扫描 topic。
     */
    private List<MemoryEntry> scan(MemoryScope scope) {
        Path topics = paths.topics(scope);
        if (!Files.isDirectory(topics)) {
            return List.of();
        }
        List<MemoryEntry> entries = new ArrayList<>();
        try (var stream = Files.list(topics)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparingLong(MemoryFileStore::lastModified).reversed())
                    .limit(maxScannedTopics)
                    .forEach(path -> {
                        try {
                            entries.add(readTopic(path, scope));
                        } catch (MemoryException invalidTopic) {
                            log.warn("忽略无效长期记忆 topic, path={}, code={}", path, invalidTopic.code(), invalidTopic);
                        }
                    });
        } catch (IOException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "扫描长期记忆失败", error);
        }
        entries.sort(Comparator
                .comparing((MemoryEntry entry) -> entry.activation() != MemoryActivation.ALWAYS)
                .thenComparing(MemoryEntry::updatedAt, Comparator.reverseOrder())
                .thenComparing(MemoryEntry::id));
        return List.copyOf(entries);
    }

    /**
     * 使用 SnakeYAML 解析 Frontmatter，并校验文件作用域和 id。
     */
    @SuppressWarnings("unchecked")
    private MemoryEntry readTopic(Path topic, MemoryScope expectedScope) {
        try {
            byte[] bytes = Files.readAllBytes(topic);
            if (bytes.length > maxTopicBytes) {
                throw new MemoryException(MemoryErrorCode.MEMORY_BUDGET_EXCEEDED, "记忆文件超过读取预算");
            }
            String raw = new String(bytes, StandardCharsets.UTF_8);
            ParsedTopic parsed = splitTopic(raw);
            Object loaded = new Yaml().load(parsed.frontmatter());
            if (!(loaded instanceof Map<?, ?> rawMap)) {
                throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆 Frontmatter 不是对象");
            }
            Map<String, Object> metadata = (Map<String, Object>) rawMap;
            String id = paths.validateId(required(metadata, "id"));
            String expectedId = topic.getFileName().toString().replaceFirst("\\.md$", "");
            if (!id.equals(expectedId)) {
                throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆 id 与文件名不一致");
            }
            MemoryScope scope = parseEnum(MemoryScope.class, required(metadata, "scope"), "scope");
            if (scope != expectedScope) {
                throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆作用域与目录不一致");
            }
            return new MemoryEntry(
                    id,
                    scope,
                    parseEnum(MemoryType.class, required(metadata, "type"), "type"),
                    parseEnum(MemoryActivation.class, required(metadata, "activation"), "activation"),
                    required(metadata, "name"),
                    required(metadata, "description"),
                    parsed.content().trim(),
                    parseInstant(required(metadata, "createdAt"), "createdAt"),
                    parseInstant(required(metadata, "updatedAt"), "updatedAt"),
                    optional(metadata, "sourceSessionId")
            );
        } catch (MemoryException error) {
            throw error;
        } catch (IOException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "读取长期记忆失败", error);
        } catch (RuntimeException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "解析长期记忆失败", error);
        }
    }

    /**
     * 将记忆元数据交给 SnakeYAML 序列化，并在其后追加正文。
     */
    private String serialize(MemoryEntry entry) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("id", entry.id());
        metadata.put("scope", entry.scope().name().toLowerCase(Locale.ROOT));
        metadata.put("type", entry.type().name().toLowerCase(Locale.ROOT));
        metadata.put("activation", entry.activation().name().toLowerCase(Locale.ROOT));
        metadata.put("name", entry.name());
        metadata.put("description", entry.description());
        metadata.put("createdAt", entry.createdAt().toString());
        metadata.put("updatedAt", entry.updatedAt().toString());
        if (entry.sourceSessionId() != null && !entry.sourceSessionId().isBlank()) {
            metadata.put("sourceSessionId", entry.sourceSessionId());
        }
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setSplitLines(false);
        String yaml = new Yaml(options).dump(metadata).trim();
        return "---\n%s\n---\n\n%s\n".formatted(yaml, entry.content().trim());
    }

    /**
     * 在调用方持有写锁时，根据全部合法 topic 生成预算内索引。
     */
    private void rebuildIndexUnderLock(MemoryScope scope) {
        List<String> lines = new ArrayList<>();
        lines.add("# Memory Index");
        lines.add("");
        for (MemoryEntry entry : scan(scope)) {
            String line = "- [%s](topics/%s.md) - %s".formatted(
                    singleLine(entry.name()),
                    entry.id(),
                    singleLine(entry.description())
            );
            if (lines.size() + 1 > maxIndexLines || byteLength(String.join("\n", lines) + "\n" + line) > maxIndexBytes) {
                lines.add("- 索引已按预算截断，topic 文件未删除。");
                break;
            }
            lines.add(line);
        }
        String index = String.join("\n", lines) + "\n";
        try {
            Files.createDirectories(paths.namespace(scope));
            atomicWrite(paths.index(scope), index);
        } catch (IOException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INDEX_REBUILD_FAILED, "重建长期记忆索引失败", error);
        }
    }

    /**
     * 在同目录临时文件完整刷盘后替换目标文件。
     */
    private void atomicWrite(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                channel.write(ByteBuffer.wrap(bytes));
                channel.force(true);
            }
            try {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                log.warn("文件系统不支持原子移动，降级为同目录替换, target={}", target);
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * 将 Frontmatter 与正文分开，拒绝缺失或未闭合的边界。
     */
    private static ParsedTopic splitTopic(String raw) {
        if (raw == null || !raw.startsWith(FRONTMATTER_BOUNDARY + "\n")) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆缺少 Frontmatter");
        }
        int end = raw.indexOf("\n" + FRONTMATTER_BOUNDARY + "\n", 4);
        if (end < 0) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆 Frontmatter 未闭合");
        }
        String frontmatter = raw.substring(4, end);
        String content = raw.substring(end + 5);
        return new ParsedTopic(frontmatter, content);
    }

    /**
     * 返回命名空间对应的应用级读写锁。
     */
    private ReentrantReadWriteLock lock(MemoryScope scope) {
        return locks.computeIfAbsent(paths.namespace(scope), ignored -> new ReentrantReadWriteLock());
    }

    /**
     * 校验文件存储硬预算，防止零值或负值绕过容量保护。
     */
    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    /**
     * 提取必需 Frontmatter 字段，缺失时按损坏 topic 处理。
     */
    private static String required(Map<String, Object> metadata, String key) {
        String value = optional(metadata, key);
        if (value == null || value.isBlank()) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆缺少字段: " + key);
        }
        return value;
    }

    /**
     * 将可选 Frontmatter 字段规范化为空值或去除首尾空白的文本。
     */
    private static String optional(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        return value == null ? null : value.toString().trim();
    }

    /**
     * 将持久化枚举值按大小写无关规则转换为受控类型。
     */
    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String field) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆字段不合法: " + field, error);
        }
    }

    /**
     * 将持久化时间转换为 Instant，并把格式错误归入记忆读取错误。
     */
    private static Instant parseInstant(String raw, String field) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_READ_FAILED, "记忆时间字段不合法: " + field, error);
        }
    }

    /**
     * 清除索引显示字段中的换行，保证每条索引只占一行。
     */
    private static String singleLine(String value) {
        return value.replaceAll("\\R+", " ").trim();
    }

    /**
     * 按 UTF-8 实际编码计算文件和索引预算消耗。
     */
    private static int byteLength(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /**
     * 获取 topic 排序时间；无法读取元数据时降级到最旧位置。
     */
    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return 0L;
        }
    }

    /**
     * Frontmatter 和正文的解析中间结果。
     */
    private record ParsedTopic(String frontmatter, String content) {
    }
}
