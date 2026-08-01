package cn.ayice.veyra.session.persistence;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 根据记忆根目录和工作区路径计算会话 transcript 的落盘位置。
 * 目录结构对齐 Claude Code 的核心形态：每个 workspace 一个 projects 子目录，每个 session 一个 JSONL 文件。
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
     * 返回当前工作区隔离后的 transcript 存储目录。
     */
    public Path projectDir() {
        return rootDir.resolve("projects").resolve(workspaceKey);
    }

    /**
     * 校验会话标识并返回其 JSONL transcript 文件路径。
     */
    public Path transcriptPath(String sessionId) {
        return projectDir().resolve(sessionId + ".jsonl");
    }

    /**
     * 将路径开头的波浪号展开为当前用户主目录。
     */
    private static Path expandHome(String memoryDir) {
        String raw = memoryDir == null || memoryDir.isBlank() ? "~/.mycc" : memoryDir;
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
}
