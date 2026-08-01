package cn.ayice.veyra.conversation.memory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

/**
 * 长期记忆路径计算器。所有物理路径都从作用域和受校验的 memory id 推导。
 */
public final class MemoryPaths {

    private static final String INDEX_FILE = "MEMORY.md";

    private final Path root;
    private final Path workspace;
    private final String projectKey;

    /**
     * 使用长期记忆根目录和当前工作区创建路径计算器。
     */
    public MemoryPaths(String memoryRoot, String workspaceRoot) {
        this.root = validateRoot(expandHome(memoryRoot).toAbsolutePath().normalize());
        this.workspace = canonicalWorkspace(workspaceRoot);
        this.projectKey = projectKey(this.workspace);
    }

    /**
     * 返回长期记忆根目录。
     */
    public Path root() {
        return root;
    }

    /**
     * 返回当前规范化工作区。
     */
    public Path workspace() {
        return workspace;
    }

    /**
     * 返回用于项目隔离的稳定键。
     */
    public String projectKey() {
        return projectKey;
    }

    /**
     * 返回指定作用域的命名空间目录。
     */
    public Path namespace(MemoryScope scope) {
        Objects.requireNonNull(scope, "scope");
        return scope == MemoryScope.USER
                ? root.resolve("user")
                : root.resolve("projects").resolve(projectKey);
    }

    /**
     * 返回指定作用域的 topic 目录。
     */
    public Path topics(MemoryScope scope) {
        return namespace(scope).resolve("topics");
    }

    /**
     * 返回指定作用域的派生索引路径。
     */
    public Path index(MemoryScope scope) {
        return namespace(scope).resolve(INDEX_FILE);
    }

    /**
     * 根据稳定 id 返回 topic 文件路径，并拒绝路径穿越字符。
     */
    public Path topic(MemoryScope scope, String id) {
        String validId = validateId(id);
        Path topic = topics(scope).resolve(validId + ".md").normalize();
        if (!topic.startsWith(topics(scope).normalize())) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "记忆标识越过允许目录");
        }
        return topic;
    }

    /**
     * 将显示名称转换为稳定、可读且可安全用于文件名的 id。
     */
    public String idFromName(String name) {
        String value = Objects.requireNonNullElse(name, "").trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return value.isBlank() ? "memory-" + shortHash(String.valueOf(name)) : value;
    }

    /**
     * 校验并返回 memory id。
     */
    public String validateId(String id) {
        String value = Objects.requireNonNullElse(id, "").trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[a-z0-9][a-z0-9_-]{0,127}")) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "记忆标识格式不合法");
        }
        return value;
    }

    /**
     * 展开用户目录缩写，不接受空路径时使用设计约定的默认目录。
     */
    private static Path expandHome(String configured) {
        String value = configured == null || configured.isBlank() ? "~/.veyra/memory" : configured.trim();
        if (value.equals("~")) {
            return Paths.get(System.getProperty("user.home"));
        }
        if (value.startsWith("~/") || value.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home")).resolve(value.substring(2));
        }
        return Paths.get(value);
    }

    /**
     * 拒绝文件系统根目录，防止错误配置把整个磁盘变成记忆命名空间。
     */
    private static Path validateRoot(Path root) {
        if (root.getParent() == null) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "长期记忆目录不能是文件系统根目录");
        }
        return root;
    }

    /**
     * 尽量将工作区解析为真实路径，目录尚不存在时退回绝对规范化路径。
     */
    private static Path canonicalWorkspace(String configured) {
        String value = configured == null || configured.isBlank()
                ? System.getProperty("user.dir")
                : configured;
        Path path = Paths.get(value).toAbsolutePath().normalize();
        try {
            return path.toRealPath();
        } catch (Exception ignored) {
            return path;
        }
    }

    /**
     * 生成可读 slug 和路径哈希组成的项目键，避免同名目录冲突。
     */
    private static String projectKey(Path workspace) {
        Path fileName = workspace.getFileName();
        String readable = fileName == null ? "workspace" : fileName.toString();
        String slug = readable.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.isBlank()) {
            slug = "workspace";
        }
        return slug + "-" + shortHash(workspace.toString()).substring(0, 12);
    }

    /**
     * 返回输入文本的 SHA-256 十六进制摘要。
     */
    private static String shortHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception error) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", error);
        }
    }
}
