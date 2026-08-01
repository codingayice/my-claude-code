package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolResult;
import cn.ayice.veyra.tooling.ValidationResult;
import cn.ayice.veyra.tooling.state.FileStateCache;

import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionDecision;
import cn.ayice.veyra.tooling.permission.PermissionSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * 创建或整体覆盖文件的工具。它会改变项目状态，因此必须经过权限系统限制写入范围。
 */
public class FileWriteTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_UNEXPECTEDLY_MODIFIED_ERROR =
            "文件在读取后已被用户或格式化工具修改。写入前请重新使用 Read 读取该文件。";

    private final FileStateCache fileStateCache;

    public FileWriteTool(FileStateCache fileStateCache) {
        this.fileStateCache = fileStateCache;
    }

    public FileWriteTool() {
        this(new FileStateCache());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "Write";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "向本地文件系统写入文件。已有文件必须先读取。小改动优先使用 Edit；新建文件或完整重写时使用 Write。相对路径只基于当前 workingDir 解析；如果要写入其他目录下的文件，必须使用绝对路径。";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Category category() {
        return Category.FILESYSTEM;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Visibility visibility() {
        return Visibility.ALL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public RiskLevel riskLevel() {
        return RiskLevel.CAUTION;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        try {
            WriteInput input = parseAndValidateInput(arguments);
            Path normalizedPath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (normalizedPath == null) {
                return PermissionDecision.deny("文件路径无效");
            }
            return PermissionSupport.checkWritePathPermission(
                    name(),
                    normalizedPath,
                    context,
                    "写入文件: " + normalizedPath
            );
        } catch (Exception e) {
            return PermissionDecision.deny("参数无效: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        try {
            WriteInput input = parseAndValidateInput(arguments);
            Path normalizedPath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (normalizedPath == null) {
                return ValidationResult.invalid("文件路径无效");
            }
            if (PermissionSupport.isUncPath(input.filePath()) || PermissionSupport.isUncPath(normalizedPath.toString())) {
                return ValidationResult.ok();
            }
            if (Files.exists(normalizedPath) && Files.isDirectory(normalizedPath)) {
                return ValidationResult.invalid("路径是目录: " + input.filePath());
            }
            return ValidationResult.ok();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        } catch (Exception e) {
            return ValidationResult.invalid("校验文件路径失败: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            WriteInput input = parseAndValidateInput(arguments);
            Path filePath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (filePath == null) {
                return ToolResult.error("文件路径无效");
            }

            WriteSnapshot snapshot = readExistingFile(filePath);
            validateWritePreconditions(filePath, snapshot);

            Path parent = filePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            writeTextContent(filePath, input.content(), snapshot.encoding());
            long mtimeMs = Files.getLastModifiedTime(filePath).toMillis();
            fileStateCache.set(filePath, FileStateCache.FileState.fromWrite(input.content(), mtimeMs));
            fileStateCache.recordModified(filePath);

            if (snapshot.exists()) {
                return ToolResult.success("文件已成功更新: " + filePath);
            }
            return ToolResult.success("文件已成功创建: " + filePath);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("写入文件失败: " + e.getMessage());
        }
    }

    /**
     * 校验覆盖目标已读取且未发生并发修改，否则拒绝写入。
     */
    private void validateWritePreconditions(Path filePath, WriteSnapshot snapshot) throws IOException {
        if (!snapshot.exists()) {
            return;
        }

        FileStateCache.FileState lastRead = fileStateCache.get(filePath);
        if (lastRead == null || lastRead.isPartialView()) {
            throw new IllegalArgumentException("写入前必须先读取文件。请先使用 Read 工具。");
        }

        long currentMtimeMs = Files.getLastModifiedTime(filePath).toMillis();
        if (currentMtimeMs > lastRead.timestamp()) {
            boolean contentUnchanged = lastRead.isFullRead() && snapshot.content().equals(lastRead.content());
            if (!contentUnchanged) {
                throw new IllegalArgumentException(FILE_UNEXPECTEDLY_MODIFIED_ERROR);
            }
        }
    }

    /**
     * 解析工具 JSON 参数并完成字段、类型和路径边界校验。
     */
    private WriteInput parseAndValidateInput(String arguments) throws IOException {
        JsonNode node = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!node.isObject()) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }

        String filePath = node.path("file_path").asText("");
        if (!node.has("content")) {
            throw new IllegalArgumentException("content 是必填字段");
        }
        JsonNode contentNode = node.get("content");
        if (!contentNode.isTextual()) {
            throw new IllegalArgumentException("content 必须是字符串");
        }
        String content = contentNode.asText();

        if (filePath.isBlank()) {
            throw new IllegalArgumentException("file_path 是必填字段");
        }
        if (PermissionSupport.containsPathTraversal(filePath)) {
            throw new IllegalArgumentException("检测到路径穿越: " + filePath);
        }
        return new WriteInput(filePath, content);
    }

    /**
     * 读取已有文件。
     */
    private WriteSnapshot readExistingFile(Path filePath) throws IOException {
        if (!Files.exists(filePath)) {
            return new WriteSnapshot("", false, StandardCharsets.UTF_8);
        }
        if (Files.isDirectory(filePath)) {
            throw new IllegalArgumentException("路径是目录: " + filePath);
        }
        Charset encoding = detectEncoding(filePath);
        return new WriteSnapshot(Files.readString(filePath, encoding), true, encoding);
    }

    /**
     * 根据文件字节标记和内容特征识别字符编码。
     */
    private Charset detectEncoding(Path filePath) throws IOException {
        byte[] buffer = new byte[4096];
        int bytesRead;
        try (RandomAccessFile raf = new RandomAccessFile(filePath.toFile(), "r")) {
            bytesRead = raf.read(buffer, 0, buffer.length);
        }
        if (bytesRead == 0) {
            return StandardCharsets.UTF_8;
        }
        if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFF && (buffer[1] & 0xFF) == 0xFE) {
            return StandardCharsets.UTF_16LE;
        }
        if (bytesRead >= 2 && (buffer[0] & 0xFF) == 0xFE && (buffer[1] & 0xFF) == 0xFF) {
            return StandardCharsets.UTF_16BE;
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * 将文本内容写入目标。
     */
    private void writeTextContent(Path filePath, String content, Charset encoding) throws IOException {
        Files.writeString(filePath, content, encoding,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /**
     * 将路径规范化为内部统一形式。
     */
    private Path normalizePath(String filePath, Path workingDir) {
        try {
            if (filePath.startsWith("~")) {
                filePath = System.getProperty("user.home") + filePath.substring(1);
            }
            Path path = Path.of(filePath);
            if (!path.isAbsolute()) {
                Path base = workingDir == null ? Path.of("").toAbsolutePath() : workingDir;
                path = base.resolve(path);
            }
            return path.normalize();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file_path", "要写入的文件路径。相对路径只基于当前 workingDir；其他目录下的文件必须使用绝对路径")
                        .addStringProperty("content", "要写入文件的完整内容")
                        .required(List.of("file_path", "content"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    /**
     * 文件写入工具校验后的目标路径和内容。
     */
    private record WriteInput(
            String filePath,
            String content
    ) {}

    /**
     * 覆盖写入前的文件内容和字符编码快照。
     */
    private record WriteSnapshot(
            String content,
            boolean exists,
            Charset encoding
    ) {}
}
