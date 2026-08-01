package cn.ayice.veyra.tool.builtin;

import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolResult;
import cn.ayice.veyra.tool.ValidationResult;
import cn.ayice.veyra.tool.state.FileStateCache;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionSupport;
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
import java.util.List;

/**
 * 读取文件内容的工具。读取时也会更新 FileStateCache，让后续编辑知道模型看到的是哪个版本。
 */
public class FileReadTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long MAX_FILE_SIZE_BYTES = 256 * 1024;
    private static final String FILE_UNCHANGED_STUB =
            "文件自上次读取后没有变化。本轮对话中较早的 Read 工具结果仍然有效，请直接参考之前的内容，不要重复读取。";

    private final FileStateCache fileStateCache;

    public FileReadTool(FileStateCache fileStateCache) {
        this.fileStateCache = fileStateCache;
    }

    public FileReadTool() {
        this(new FileStateCache());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "Read";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "从本地文件系统读取文本文件。可使用 offset 和 limit 按行范围读取。相对路径只基于当前 workingDir 解析；如果要读取其他目录下的文件，必须使用绝对路径。";
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
        return RiskLevel.SAFE;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        try {
            ReadInput input = parseInput(arguments);
            Path normalizedPath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (normalizedPath == null) {
                return PermissionDecision.deny("文件路径无效");
            }
            return PermissionSupport.checkReadPathPermission(
                    name(),
                    normalizedPath,
                    context,
                    "读取文件: " + normalizedPath
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
            ReadInput input = parseInput(arguments);
            Path normalizedPath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (normalizedPath == null) {
                return ValidationResult.invalid("文件路径无效");
            }
            if (PermissionSupport.isUncPath(input.filePath()) || PermissionSupport.isUncPath(normalizedPath.toString())) {
                return ValidationResult.ok();
            }
            if (!Files.exists(normalizedPath)) {
                return ValidationResult.invalid("文件不存在: " + input.filePath());
            }
            if (Files.isDirectory(normalizedPath)) {
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
            ReadInput input = parseInput(arguments);
            Path normalizedPath = normalizePath(input.filePath(), context == null ? null : context.workingDir());
            if (normalizedPath == null) {
                return ToolResult.error("文件路径无效");
            }
            if (!Files.exists(normalizedPath)) {
                return ToolResult.error("文件不存在: " + input.filePath());
            }
            if (Files.isDirectory(normalizedPath)) {
                return ToolResult.error("路径是目录: " + input.filePath());
            }
            return readFile(normalizedPath, input.offset(), input.limit());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("读取文件失败: " + e.getMessage());
        }
    }

    /**
     * 解析输入并返回输入。
     */
    private ReadInput parseInput(String arguments) throws IOException {
        JsonNode node = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!node.isObject()) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }

        String filePath = node.path("file_path").asText("");
        int offset = optionalInt(node, "offset", 1);
        Integer limit = optionalInteger(node, "limit");

        if (filePath.isBlank()) {
            throw new IllegalArgumentException("file_path 是必填字段");
        }
        if (offset < 1) {
            throw new IllegalArgumentException("offset 必须大于或等于 1");
        }
        if (limit != null && limit < 1) {
            throw new IllegalArgumentException("limit 必须大于 0");
        }
        if (PermissionSupport.containsPathTraversal(filePath)) {
            throw new IllegalArgumentException("检测到路径穿越: " + filePath);
        }
        return new ReadInput(filePath, offset, limit);
    }

    /**
     * 读取可选整数字段；缺失时返回默认值，类型错误时拒绝输入。
     */
    private int optionalInt(JsonNode node, String fieldName, int defaultValue) {
        Integer value = optionalInteger(node, fieldName);
        return value == null ? defaultValue : value;
    }

    /**
     * 读取可选整数字段，并拒绝非整数值。
     */
    private Integer optionalInteger(JsonNode node, String fieldName) {
        if (!node.has(fieldName)) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(fieldName + " 必须是整数");
        }
        return value.asInt();
    }

    /**
     * 读取文件。
     */
    private ToolResult readFile(Path filePath, int offset, Integer limit) throws IOException {
        long mtimeMs = -1;
        try {
            mtimeMs = Files.getLastModifiedTime(filePath).toMillis();
        } catch (IOException ignored) {
        }

        if (mtimeMs >= 0) {
            FileStateCache.FileState existingState = fileStateCache.get(filePath);
            if (existingState != null && !existingState.isPartialView() && existingState.isFromRead()) {
                boolean rangeMatch = existingState.offset() == offset
                        && (existingState.limit() == null ? limit == null : existingState.limit().equals(limit));
                if (rangeMatch && mtimeMs == existingState.timestamp()) {
                    return ToolResult.success(FILE_UNCHANGED_STUB);
                }
            }
        }

        if (limit == null) {
            long fileSize = Files.size(filePath);
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                return ToolResult.error(String.format(
                        "文件大小 (%s) 超过允许上限 (%s)。请使用 offset 和 limit 读取文件的一部分。",
                        formatFileSize(fileSize), formatFileSize(MAX_FILE_SIZE_BYTES)));
            }
        }

        Charset encoding = detectEncoding(filePath);
        String content = Files.readString(filePath, encoding);

        if (mtimeMs < 0) {
            try {
                mtimeMs = Files.getLastModifiedTime(filePath).toMillis();
            } catch (IOException e) {
                mtimeMs = System.currentTimeMillis();
            }
        }
        fileStateCache.set(filePath, FileStateCache.FileState.fromRead(content, mtimeMs, offset, limit));

        if (content.isEmpty()) {
            return ToolResult.success("<system-reminder>提醒: 文件存在，但内容为空。</system-reminder>");
        }

        return buildResult(content, offset, limit);
    }

    /**
     * 根据当前输入构建结果。
     */
    private ToolResult buildResult(String content, int offset, Integer limit) {
        String[] allLines = content.split("\\R", -1);
        int totalLines = allLines.length;

        if (offset > totalLines) {
            return ToolResult.success(String.format(
                    "<system-reminder>提醒: 文件存在，但行数少于指定 offset。文件共有 %d 行，你请求从第 %d 行开始读取。</system-reminder>",
                    totalLines, offset));
        }

        int startIdx = offset - 1;
        int endIdx = limit == null ? totalLines : Math.min(startIdx + limit, totalLines);
        StringBuilder sb = new StringBuilder();
        int width = String.valueOf(endIdx).length();

        for (int i = startIdx; i < endIdx; i++) {
            int lineNum = i + 1;
            String paddedNum = String.format("%" + width + "d", lineNum);
            sb.append(paddedNum).append(" | ").append(allLines[i]);
            if (i < endIdx - 1) {
                sb.append("\n");
            }
        }

        return ToolResult.success(sb.toString());
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
        if (bytesRead >= 3 && (buffer[0] & 0xFF) == 0xEF && (buffer[1] & 0xFF) == 0xBB && (buffer[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        return StandardCharsets.UTF_8;
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
     * 将输入格式化为文件大小。
     */
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(name()).description(description())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("file_path", "要读取的文件路径。相对路径只基于当前 workingDir；其他目录下的文件必须使用绝对路径")
                        .addIntegerProperty("offset", "起始行号，从 1 开始，可选")
                        .addIntegerProperty("limit", "读取的行数，可选")
                        .required(List.of("file_path"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    /**
     * 文件读取工具校验后的路径、偏移量和行数限制。
     */
    private record ReadInput(String filePath, int offset, Integer limit) {}
}
