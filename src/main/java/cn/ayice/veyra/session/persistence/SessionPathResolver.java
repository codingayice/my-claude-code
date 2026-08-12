package cn.ayice.veyra.session.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 根据会话存储根目录和工作区路径计算会话持久化文件的落盘位置。
 * 每个 workspace 使用一个 projects 子目录，每个 session 使用独立 JSONL 文件。
 */
public class SessionPathResolver {

    private static final Logger log = LoggerFactory.getLogger(SessionPathResolver.class);

    private final Path rootDir;
    private final String workspaceKey;

    public SessionPathResolver(String memoryDir, String workspace) {
        this.rootDir = expandHome(memoryDir);
        this.workspaceKey = sanitizeWorkspace(workspace);
    }

    /**
     * 返回当前工作区隔离后的 Journal 存储目录。
     */
    public Path projectDir() {
        return rootDir.resolve("projects").resolve(workspaceKey);
    }

    /**
     * 返回当前会话唯一的 Durable Journal 路径。
     */
    public Path journalPath(String sessionId) {
        return sessionDir(sessionId).resolve("events.jsonl");
    }

    /** 返回一个 Session 的独占持久化目录。 */
    public Path sessionDir(String sessionId) {
        return projectDir().resolve(requireSafeId(sessionId, "sessionId"));
    }

    /** 返回 Session 的可重建 Run 图索引路径。 */
    public Path sessionIndexPath(String sessionId) {
        return sessionDir(sessionId).resolve("session-index.json");
    }

    /** 返回指定终态 Run 的不可变 Snapshot 路径。 */
    public Path runSnapshotPath(String sessionId, String runId) {
        return sessionDir(sessionId)
                .resolve("snapshots")
                .resolve(requireSafeId(runId, "runId") + ".snapshot.json");
    }

    /**
     * 将路径开头的波浪号展开为当前用户主目录。
     */
    private static Path expandHome(String memoryDir) {
        String raw = memoryDir == null || memoryDir.isBlank() ? "~/.veyra/sessions" : memoryDir;
        if (raw.equals("~")) {
            return Paths.get(System.getProperty("user.home"));
        }
        if (raw.startsWith("~/") || raw.startsWith("~\\")) {
            return Paths.get(System.getProperty("user.home")).resolve(raw.substring(2));
        }
        return Paths.get(raw);
    }

    /**
     * 将工作区路径转换为稳定目录名，并附加哈希避免同名冲突。
     */
    static String sanitizeWorkspace(String workspace) {
        String raw = workspace == null || workspace.isBlank()
                ? System.getProperty("user.dir", "default-project")
                : workspace;
        String normalized = raw.replace('\\', '/');
        if (normalized.matches("^[A-Za-z]:/.*")) {
            normalized = normalized.charAt(0) + "--" + normalized.substring(3);
        }
        normalized = normalized
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("(^-+|-+$)", "");
        return normalized.isBlank() ? shortHash(raw) : normalized;
    }

    /**
     * 返回输入文本 SHA-256 摘要的短十六进制前缀。
     */
    private static String shortHash(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 10);
        } catch (Exception e) {
            log.warn("无法使用 SHA-256 生成工作区键，退回 hashCode", e);
            return Integer.toHexString(String.valueOf(text).hashCode());
        }
    }

    /** 仅允许系统生成的简单标识参与路径计算。 */
    private static String requireSafeId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(name + " contains unsafe path characters");
        }
        return value;
    }
}
