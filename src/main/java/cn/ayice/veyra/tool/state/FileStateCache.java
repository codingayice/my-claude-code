package cn.ayice.veyra.tool.state;


import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件工具共享的文件状态缓存。
 *
 * 设计要点：
 * <ol>
 *   <li>Read 工具写入缓存时会记录 offset / limit，表示模型只看过文件片段。</li>
 *   <li>Edit / Write 工具刷新缓存时 offset / limit 为 null，表示缓存的是完整文件。</li>
 *   <li>Read 去重依赖 offset / limit 匹配以及 mtime 未变化。</li>
 *   <li>Edit / Write 执行前依赖 mtime 和内容比较，避免基于旧文件状态误写。</li>
 * </ol>
 */
public class FileStateCache {

    private static final int MAX_ENTRIES = 100;
    private static final long MAX_SIZE_BYTES = 25 * 1024 * 1024; // 25MB

    private final LinkedHashMap<String, FileState> cache;
    private final LinkedHashMap<String, Boolean> modifiedPaths = new LinkedHashMap<>(16, 0.75f, true);
    private long currentSizeBytes = 0;

    public FileStateCache() {
        this.cache = new LinkedHashMap<>(100, 0.75f, true) {
            /**
             * {@inheritDoc}
             */
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, FileState> eldest) {
                if (size() > MAX_ENTRIES) {
                    currentSizeBytes -= eldest.getValue().contentSizeBytes();
                    return true;
                }
                return false;
            }
        };
    }

    /**
     * 文件状态
     *
     * @param content      文件内容
     * @param timestamp    文件修改时间 (mtimeMs)
     * @param offset       读取起始行（Read 工具设置，Edit/Write 为 null）
     * @param limit        读取行数限制（Read 工具设置，Edit/Write 为 null）
     * @param isPartialView 是否为部分视图（如自动注入的 CLAUDE.md）
     */
    public record FileState (
            String content,
            long timestamp,
            Integer offset,
            Integer limit,
            boolean isPartialView
    ) {
        public FileState {
            // 紧凑构造器，用于参数验证
        }

        /**
         * 创建 Read 工具的缓存条目
         */
        public static FileState fromRead(String content, long timestamp, int offset, Integer limit) {
            return new FileState(content, timestamp, offset, limit, false);
        }

        /**
         * 创建 Edit/Write 工具的缓存条目
         */
        public static FileState fromWrite(String content, long timestamp) {
            return new FileState(content, timestamp, null, null, false);
        }

        /**
         * 内容的 UTF-8 字节大小
         */
        public long contentSizeBytes() {
            return content == null ? 0 : content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }

        /**
         * 是否为 Read 工具写入的条目
         */
        public boolean isFromRead() {
            return offset != null;
        }

        /**
         * 是否为完整读取（无 offset/limit 限制）
         */
        public boolean isFullRead() {
            if (isPartialView) {
                return false;
            }
            return (offset == null && limit == null)
                    || (offset != null && offset == 1 && limit == null);
        }
    }

    /**
     * 获取缓存条目
     */
    public synchronized FileState get(Path filePath) {
        return cache.get(normalizePath(filePath));
    }

    /**
     * 设置缓存条目
     */
    public synchronized void set(Path filePath, FileState state) {
        String key = normalizePath(filePath);

        // 移除旧条目，更新大小
        FileState old = cache.get(key);
        if (old != null) {
            currentSizeBytes -= old.contentSizeBytes();
        }

        // 检查大小限制
        long newSize = currentSizeBytes + state.contentSizeBytes();
        if (newSize > MAX_SIZE_BYTES && !cache.isEmpty()) {
            // 淘汰最旧的条目直到有足够空间
            evictUntilFit(state.contentSizeBytes());
        }

        cache.put(key, state);
        currentSizeBytes += state.contentSizeBytes();
    }

    /**
     * 删除缓存条目
     */
    public synchronized boolean delete(Path filePath) {
        FileState removed = cache.remove(normalizePath(filePath));
        if (removed != null) {
            currentSizeBytes -= removed.contentSizeBytes();
            return true;
        }
        return false;
    }

    /**
     * 检查是否包含指定文件
     */
    public synchronized boolean has(Path filePath) {
        return cache.containsKey(normalizePath(filePath));
    }

    /**
     * 清空缓存
     */
    public synchronized void clear() {
        cache.clear();
        modifiedPaths.clear();
        currentSizeBytes = 0;
    }

    /**
     * 获取缓存条目数
     */
    public synchronized int size() {
        return cache.size();
    }

    /**
     * 获取所有缓存的文件路径
     */
    public synchronized java.util.Set<String> keys() {
        return Collections.unmodifiableSet(new java.util.LinkedHashSet<>(cache.keySet()));
    }

    /**
     * 返回已完整读取文件路径的线程安全快照。
     */
    public synchronized java.util.List<String> fullReadPaths() {
        return cache.entrySet().stream()
                .filter(entry -> entry.getValue() != null && entry.getValue().isFullRead())
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 记录一次成功 Edit 或 Write 的规范化绝对路径；重复修改只更新最近顺序。
     */
    public synchronized void recordModified(Path filePath) {
        String normalized = normalizePath(filePath);
        modifiedPaths.remove(normalized);
        modifiedPaths.put(normalized, Boolean.TRUE);
        if (modifiedPaths.size() > MAX_ENTRIES) {
            var iterator = modifiedPaths.entrySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    /**
     * 返回最近优先的不可变修改路径列表。
     */
    public synchronized List<Path> recentModifiedPaths(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        List<String> paths = new ArrayList<>(modifiedPaths.keySet());
        Collections.reverse(paths);
        return paths.stream()
                .limit(limit)
                .map(Path::of)
                .toList();
    }

    /**
     * 路径规范化（确保跨平台一致性）
     */
    private String normalizePath(Path filePath) {
        return filePath.normalize().toAbsolutePath().toString();
    }

    /**
     * 淘汰条目直到有足够空间
     */
    private void evictUntilFit(long requiredBytes) {
        var iterator = cache.entrySet().iterator();
        while (iterator.hasNext() && currentSizeBytes + requiredBytes > MAX_SIZE_BYTES) {
            var entry = iterator.next();
            currentSizeBytes -= entry.getValue().contentSizeBytes();
            iterator.remove();
        }
    }
}
