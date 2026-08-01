package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolResult;
import cn.ayice.veyra.tooling.ValidationResult;

import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionDecision;
import cn.ayice.veyra.tooling.permission.PermissionSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 按模式搜索文件内容的工具。它用于在代码、配置和日志片段中定位相关文本。
 */
public class GrepTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> VCS_DIRECTORIES_TO_EXCLUDE = List.of(".git", ".svn", ".hg", ".bzr", ".jj", ".sl");
    private static final int DEFAULT_HEAD_LIMIT = 250;
    private static final int MAX_BUFFER_BYTES = 20_000_000;
    private static final long TIMEOUT_MS = 20_000;
    private final Executor ioExecutor;

    public GrepTool(Executor ioExecutor) {
        this.ioExecutor = ioExecutor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "Grep";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "基于 ripgrep 的强大搜索工具。\n\n" +
                "使用规则:\n" +
                "- 搜索任务应优先使用 Grep，不要通过 Bash 直接调用 grep 或 rg。Grep 已针对权限和访问控制做过适配。\n" +
                "- 支持完整正则语法，例如 \"log.*Error\"、\"function\\\\s+\\\\w+\"\n" +
                "- 可用 glob 参数过滤文件，例如 \"*.js\"、\"**/*.tsx\"；也可用 type 参数过滤类型，例如 \"js\"、\"py\"、\"rust\"\n" +
                "- output_mode 可选: \"content\" 显示匹配行，\"files_with_matches\" 仅显示文件路径，\"count\" 显示匹配数量\n" +
                "- pattern 语法使用 ripgrep，而不是传统 grep\n" +
                "- path 使用相对路径时只基于当前 workingDir；如果要搜索其他目录下的文件或目录，必须使用绝对路径\n" +
                "- 默认只在单行内匹配；如需跨行匹配，设置 multiline: true";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Category category() {
        return Category.SEARCH;
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
            GrepInput input = parseAndValidateInput(arguments);
            Path searchPath = normalizePath(input.path(), context == null ? null : context.workingDir());
            if (searchPath == null) {
                return PermissionDecision.deny("搜索路径无效");
            }
            return PermissionSupport.checkReadPathPermission(
                    name(),
                    searchPath,
                    context,
                    "搜索文件: " + searchPath
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
            GrepInput input = parseAndValidateInput(arguments);
            Path searchPath = normalizePath(input.path(), context == null ? null : context.workingDir());
            if (searchPath == null) {
                return ValidationResult.invalid("搜索路径无效");
            }
            if (PermissionSupport.isUncPath(input.path()) || PermissionSupport.isUncPath(searchPath.toString())) {
                return ValidationResult.ok();
            }
            if (!Files.exists(searchPath)) {
                return ValidationResult.invalid("路径不存在: " + (input.path() == null ? searchPath : input.path()));
            }
            return ValidationResult.ok();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        } catch (Exception e) {
            return ValidationResult.invalid("校验搜索路径失败: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            GrepInput input = parseAndValidateInput(arguments);
            Path workingDir = context == null || context.workingDir() == null
                    ? Path.of("").toAbsolutePath().normalize()
                    : context.workingDir().normalize().toAbsolutePath();
            Path searchPath = normalizePath(input.path(), workingDir);
            if (searchPath == null) {
                return ToolResult.error("搜索路径无效");
            }
            if (!Files.exists(searchPath)) {
                return ToolResult.error("路径不存在: " + (input.path() == null ? searchPath : input.path()));
            }

            ProcessResult processResult = runRipgrep(input, searchPath, workingDir);
            if (processResult.timedOut()) {
                return ToolResult.error("Ripgrep 搜索在 " + (TIMEOUT_MS / 1000) +
                        " 秒后超时。请尝试更具体的路径或 pattern。");
            }
            if (processResult.exitCode() == 1) {
                return formatOutput(input, List.of(), searchPath, workingDir);
            }
            if (processResult.exitCode() != 0) {
                return ToolResult.error(ripgrepErrorMessage(processResult));
            }

            List<String> rawLines = parseLines(processResult.stdout());
            return formatOutput(input, rawLines, searchPath, workingDir);
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (IOException e) {
            return ToolResult.error(ripgrepStartError(e));
        } catch (Exception e) {
            return ToolResult.error("搜索文件失败: " + e.getMessage());
        }
    }

    /**
     * 解析工具 JSON 参数并完成字段、类型和路径边界校验。
     */
    private GrepInput parseAndValidateInput(String arguments) throws IOException {
        JsonNode node = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!node.isObject()) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }
        if (!node.has("pattern")) {
            throw new IllegalArgumentException("pattern 是必填字段");
        }
        if (!node.get("pattern").isTextual()) {
            throw new IllegalArgumentException("pattern 必须是字符串");
        }
        String pattern = node.get("pattern").asText();
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("pattern 是必填字段");
        }

        String path = optionalString(node, "path");
        if (path != null && PermissionSupport.containsPathTraversal(path)) {
            throw new IllegalArgumentException("检测到路径穿越: " + path);
        }
        String glob = optionalString(node, "glob");
        String type = optionalString(node, "type");
        String outputMode = optionalString(node, "output_mode");
        if (outputMode == null) {
            outputMode = "files_with_matches";
        }
        if (!outputMode.equals("content") && !outputMode.equals("files_with_matches") && !outputMode.equals("count")) {
            throw new IllegalArgumentException("output_mode 必须是以下之一: content, files_with_matches, count");
        }

        return new GrepInput(
                pattern,
                path,
                glob,
                outputMode,
                optionalNonNegativeInteger(node, "-B"),
                optionalNonNegativeInteger(node, "-A"),
                optionalNonNegativeInteger(node, "-C"),
                optionalNonNegativeInteger(node, "context"),
                optionalBoolean(node, "-n", true),
                optionalBoolean(node, "-i", false),
                type,
                optionalNonNegativeInteger(node, "head_limit"),
                optionalNonNegativeInteger(node, "offset", 0),
                optionalBoolean(node, "multiline", false)
        );
    }

    /**
     * 读取可选字符串字段，缺失时返回空值。
     */
    private String optionalString(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return null;
        }
        JsonNode value = node.get(fieldName);
        if (!value.isTextual()) {
            throw new IllegalArgumentException(fieldName + " 必须是字符串");
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    /**
     * 读取可选非负整数字段，并校验下界。
     */
    private Integer optionalNonNegativeInteger(JsonNode node, String fieldName) {
        return optionalNonNegativeInteger(node, fieldName, null);
    }

    /**
     * 读取可选非负整数字段，并校验下界。
     */
    private Integer optionalNonNegativeInteger(JsonNode node, String fieldName, Integer defaultValue) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(fieldName);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(fieldName + " 必须是整数");
        }
        int intValue = value.asInt();
        if (intValue < 0) {
            throw new IllegalArgumentException(fieldName + " 必须大于或等于 0");
        }
        return intValue;
    }

    /**
     * 读取可选布尔字段，并拒绝非布尔值。
     */
    private boolean optionalBoolean(JsonNode node, String fieldName, boolean defaultValue) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(fieldName);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException(fieldName + " 必须是布尔值");
        }
        return value.asBoolean();
    }

    /**
     * 启动 ripgrep、收集标准输出和错误输出，并返回退出码。
     */
    private ProcessResult runRipgrep(GrepInput input, Path searchPath, Path workingDir) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add("rg");
        command.add("--hidden");
        for (String directory : VCS_DIRECTORIES_TO_EXCLUDE) {
            command.add("--glob");
            command.add("!" + directory);
        }
        command.add("--max-columns");
        command.add("500");
        command.add("--with-filename");

        if (input.multiline()) {
            command.add("-U");
            command.add("--multiline-dotall");
        }
        if (input.caseInsensitive()) {
            command.add("-i");
        }
        if (input.outputMode().equals("files_with_matches")) {
            command.add("-l");
        } else if (input.outputMode().equals("count")) {
            command.add("-c");
        }
        if (input.showLineNumbers() && input.outputMode().equals("content")) {
            command.add("-n");
        }
        if (input.outputMode().equals("content")) {
            if (input.context() != null) {
                command.add("-C");
                command.add(input.context().toString());
            } else if (input.contextC() != null) {
                command.add("-C");
                command.add(input.contextC().toString());
            } else {
                if (input.contextBefore() != null) {
                    command.add("-B");
                    command.add(input.contextBefore().toString());
                }
                if (input.contextAfter() != null) {
                    command.add("-A");
                    command.add(input.contextAfter().toString());
                }
            }
        }

        if (input.pattern().startsWith("-")) {
            command.add("-e");
        }
        command.add(input.pattern());

        if (input.type() != null) {
            command.add("--type");
            command.add(input.type());
        }
        if (input.glob() != null) {
            for (String globPattern : splitGlobPatterns(input.glob())) {
                command.add("--glob");
                command.add(globPattern);
            }
        }
        command.add(searchPath.toString());

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDir.toFile());
        Process process = builder.start();

        CompletableFuture<String> stdout = CompletableFuture.supplyAsync(
                () -> readStream(process.getInputStream()), ioExecutor);
        CompletableFuture<String> stderr = CompletableFuture.supplyAsync(
                () -> readStream(process.getErrorStream()), ioExecutor);
        boolean finished = process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            return new ProcessResult(-1, stdout.join(), stderr.join(), true);
        }

        return new ProcessResult(process.exitValue(), stdout.join(), stderr.join(), false);
    }

    /**
     * 按逗号拆分 Glob 过滤条件并丢弃空模式。
     */
    private List<String> splitGlobPatterns(String glob) {
        List<String> patterns = new ArrayList<>();
        for (String rawPattern : glob.split("\\s+")) {
            if (rawPattern.isBlank()) {
                continue;
            }
            if (rawPattern.contains("{") && rawPattern.contains("}")) {
                patterns.add(rawPattern);
            } else {
                for (String part : rawPattern.split(",")) {
                    if (!part.isBlank()) {
                        patterns.add(part);
                    }
                }
            }
        }
        return patterns;
    }

    /**
     * 读取数据流。
     */
    private String readStream(InputStream stream) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = stream.read(buffer)) >= 0) {
                int allowed = Math.min(read, Math.max(0, MAX_BUFFER_BYTES - total));
                if (allowed > 0) {
                    output.write(buffer, 0, allowed);
                }
                total += read;
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * 将输入格式化为输出。
     */
    private ToolResult formatOutput(GrepInput input, List<String> rawLines, Path searchPath, Path workingDir) {
        if (input.outputMode().equals("content")) {
            LimitedItems<String> limited = applyHeadLimit(rawLines, input.headLimit(), input.offset());
            List<String> lines = limited.items().stream()
                    .map(line -> relativizeContentLine(line, workingDir, input.showLineNumbers()))
                    .toList();
            String content = lines.isEmpty() ? "未找到匹配项" : String.join("\n", lines);
            String limitInfo = formatLimitInfo(limited.appliedLimit(), input.offset());
            if (!limitInfo.isBlank()) {
                content += "\n\n[分页显示结果: " + limitInfo + "]";
            }
            return ToolResult.success(content);
        }

        if (input.outputMode().equals("count")) {
            LimitedItems<String> limited = applyHeadLimit(rawLines, input.headLimit(), input.offset());
            List<String> lines = limited.items().stream()
                    .map(line -> relativizeCountLine(line, workingDir))
                    .toList();
            int totalMatches = 0;
            int fileCount = 0;
            for (String line : lines) {
                int colonIndex = line.lastIndexOf(':');
                if (colonIndex > 0) {
                    try {
                        totalMatches += Integer.parseInt(line.substring(colonIndex + 1));
                        fileCount++;
                    } catch (NumberFormatException ignored) {
                        // Ignore malformed count lines from ripgrep.
                    }
                }
            }
            String content = lines.isEmpty() ? "未找到匹配项" : String.join("\n", lines);
            String limitInfo = formatLimitInfo(limited.appliedLimit(), input.offset());
            content += "\n\n共找到 " + totalMatches + " 处匹配，分布在 " + fileCount + " 个文件中。";
            if (!limitInfo.isBlank()) {
                content += " 分页: " + limitInfo;
            }
            return ToolResult.success(content);
        }

        List<FileMatch> matches = new ArrayList<>();
        for (String rawLine : rawLines) {
            Path path = resolveRipgrepPath(rawLine, searchPath, workingDir);
            long mtime = 0;
            try {
                mtime = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException ignored) {
                // Deleted or unreadable files sort last.
            }
            matches.add(new FileMatch(path, mtime));
        }
        matches.sort(Comparator.comparingLong(FileMatch::mtimeMs).reversed()
                .thenComparing(match -> match.path().toString()));

        LimitedItems<FileMatch> limited = applyHeadLimit(matches, input.headLimit(), input.offset());
        List<String> filenames = limited.items().stream()
                .map(match -> toRelativePath(match.path().toString(), workingDir))
                .toList();
        if (filenames.isEmpty()) {
            return ToolResult.success("未找到文件");
        }

        String limitInfo = formatLimitInfo(limited.appliedLimit(), input.offset());
        StringBuilder result = new StringBuilder();
        result.append("找到 ").append(filenames.size()).append(" 个文件");
        if (!limitInfo.isBlank()) {
            result.append(" ").append(limitInfo);
        }
        result.append("\n").append(String.join("\n", filenames));
        return ToolResult.success(result.toString());
    }

    /**
     * 按偏移量和上限截取结果，并标记是否仍有未返回项。
     */
    private <T> LimitedItems<T> applyHeadLimit(List<T> items, Integer limit, int offset) {
        int safeOffset = Math.min(offset, items.size());
        if (limit != null && limit == 0) {
            return new LimitedItems<>(items.subList(safeOffset, items.size()), null);
        }

        int effectiveLimit = limit == null ? DEFAULT_HEAD_LIMIT : limit;
        int end = Math.min(items.size(), safeOffset + effectiveLimit);
        boolean truncated = items.size() - safeOffset > effectiveLimit;
        return new LimitedItems<>(items.subList(safeOffset, end), truncated ? effectiveLimit : null);
    }

    /**
     * 生成结果截断和偏移量信息；未限制时返回空字符串。
     */
    private String formatLimitInfo(Integer appliedLimit, int offset) {
        List<String> parts = new ArrayList<>();
        if (appliedLimit != null) {
            parts.add("limit: " + appliedLimit);
        }
        if (offset > 0) {
            parts.add("offset: " + offset);
        }
        return String.join(", ", parts);
    }

    /**
     * 把 ripgrep 内容模式输出中的绝对路径转换为工作区相对路径。
     */
    private String relativizeContentLine(String line, Path workingDir, boolean hasLineNumber) {
        if (hasLineNumber) {
            LineParts parts = splitContentLineWithLineNumber(line);
            if (parts != null) {
                return toRelativePath(parts.filePath(), workingDir) + ":" + parts.rest();
            }
        }

        int separator = firstOutputSeparator(line);
        if (separator > 0) {
            String filePath = line.substring(0, separator);
            String rest = line.substring(separator);
            return toRelativePath(filePath, workingDir) + rest;
        }
        return line;
    }

    /**
     * 解析 ripgrep 内容行中的路径、行号和正文三部分。
     */
    private LineParts splitContentLineWithLineNumber(String line) {
        int separator = firstOutputSeparator(line);
        while (separator > 0) {
            int nextSeparator = line.indexOf(':', separator + 1);
            if (nextSeparator < 0) {
                return null;
            }
            String maybeLineNumber = line.substring(separator + 1, nextSeparator);
            if (isDigits(maybeLineNumber)) {
                return new LineParts(line.substring(0, separator), line.substring(separator + 1));
            }
            separator = line.indexOf(':', separator + 1);
        }
        return null;
    }

    /**
     * 把 ripgrep 计数模式输出中的绝对路径转换为工作区相对路径。
     */
    private String relativizeCountLine(String line, Path workingDir) {
        int colonIndex = line.lastIndexOf(':');
        if (colonIndex <= 0) {
            return line;
        }
        String filePath = line.substring(0, colonIndex);
        String count = line.substring(colonIndex);
        return toRelativePath(filePath, workingDir) + count;
    }

    /**
     * 定位 ripgrep 输出中路径字段与后续字段之间的分隔符。
     */
    private int firstOutputSeparator(String line) {
        int start = looksLikeWindowsDrivePath(line) ? 2 : 0;
        return line.indexOf(':', start);
    }

    /**
     * 判断输出行是否以 Windows 盘符绝对路径开头。
     */
    private boolean looksLikeWindowsDrivePath(String line) {
        return line.length() >= 3
                && Character.isLetter(line.charAt(0))
                && line.charAt(1) == ':'
                && (line.charAt(2) == '\\' || line.charAt(2) == '/');
    }

    /**
     * 判断非空字符串是否完全由十进制数字组成。
     */
    private boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 将绝对路径转换为相对工作目录、使用正斜杠的展示路径。
     */
    private String toRelativePath(String pathText, Path workingDir) {
        try {
            Path path = Path.of(pathText);
            if (!path.isAbsolute()) {
                path = workingDir.resolve(path);
            }
            Path normalizedPath = path.normalize().toAbsolutePath();
            Path normalizedWorkingDir = workingDir.normalize().toAbsolutePath();
            if (normalizedPath.startsWith(normalizedWorkingDir)) {
                return normalizedWorkingDir.relativize(normalizedPath).toString();
            }
            return normalizedPath.toString();
        } catch (InvalidPathException e) {
            return pathText;
        }
    }

    /**
     * 优先使用配置路径，否则在 PATH 中定位 ripgrep 可执行文件。
     */
    private Path resolveRipgrepPath(String pathText, Path searchPath, Path workingDir) {
        try {
            Path path = Path.of(pathText);
            if (path.isAbsolute()) {
                return path.normalize().toAbsolutePath();
            }

            Path workingDirCandidate = workingDir.resolve(path).normalize().toAbsolutePath();
            if (Files.exists(workingDirCandidate)) {
                return workingDirCandidate;
            }
            if (Files.isDirectory(searchPath)) {
                Path searchPathCandidate = searchPath.resolve(path).normalize().toAbsolutePath();
                if (Files.exists(searchPathCandidate)) {
                    return searchPathCandidate;
                }
            }
            return workingDirCandidate;
        } catch (InvalidPathException e) {
            return workingDir.resolve(pathText).normalize().toAbsolutePath();
        }
    }

    /**
     * 解析输入并返回文本行。
     */
    private List<String> parseLines(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String line : stdout.strip().split("\\R")) {
            if (!line.isBlank()) {
                lines.add(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            }
        }
        return lines;
    }

    /**
     * 从进程输出中提取适合返回给调用方的 ripgrep 错误摘要。
     */
    private String ripgrepErrorMessage(ProcessResult processResult) {
        String stderr = processResult.stderr() == null ? "" : processResult.stderr().trim();
        if (!stderr.isBlank()) {
            return "ripgrep 执行失败: " + stderr;
        }
        return "ripgrep 执行失败，退出码: " + processResult.exitCode();
    }

    /**
     * 将 ripgrep 启动异常转换为包含安装提示的稳定错误消息。
     */
    private String ripgrepStartError(IOException e) {
        String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (message.contains("cannot run program") || message.contains("createprocess error=2") || message.contains("no such file")) {
            return "Grep 需要 ripgrep (rg)，但 PATH 中未找到 rg。";
        }
        return "启动 ripgrep 失败: " + e.getMessage();
    }

    /**
     * 根据数量选择英文单数或复数形式。
     */
    private String plural(int count, String singular) {
        return count == 1 ? singular : singular + "s";
    }

    /**
     * 将路径规范化为内部统一形式。
     */
    private Path normalizePath(String path, Path workingDir) {
        try {
            Path base = workingDir == null ? Path.of("").toAbsolutePath().normalize() : workingDir.normalize().toAbsolutePath();
            if (path == null || path.isBlank()) {
                return base;
            }
            String expanded = expandHome(path);
            Path normalizedPath = Path.of(expanded);
            if (!normalizedPath.isAbsolute()) {
                normalizedPath = base.resolve(normalizedPath);
            }
            return normalizedPath.normalize().toAbsolutePath();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 将路径开头的波浪号展开为当前用户主目录。
     */
    private String expandHome(String path) {
        if (path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
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
                        .addStringProperty("pattern", "要在文件内容中搜索的正则表达式")
                        .addStringProperty("path", "要搜索的文件或目录，默认当前 workingDir。相对路径只基于当前 workingDir；其他目录下的文件或目录必须使用绝对路径")
                        .addStringProperty("glob", "用于过滤文件的 glob 模式，例如 \"*.js\" 或 \"*.{ts,tsx}\"")
                        .addStringProperty("output_mode", "输出模式: content、files_with_matches 或 count，默认 files_with_matches")
                        .addIntegerProperty("-B", "每个匹配项前显示的行数，要求 output_mode: content")
                        .addIntegerProperty("-A", "每个匹配项后显示的行数，要求 output_mode: content")
                        .addIntegerProperty("-C", "每个匹配项前后显示的行数，要求 output_mode: content")
                        .addIntegerProperty("context", "每个匹配项前后显示的行数，要求 output_mode: content")
                        .addBooleanProperty("-n", "content 模式是否显示行号，默认 true")
                        .addBooleanProperty("-i", "是否忽略大小写")
                        .addStringProperty("type", "要搜索的文件类型，例如 java、js、py、rust、go")
                        .addIntegerProperty("head_limit", "限制输出前 N 行或 N 项，默认 250；传 0 表示不限制")
                        .addIntegerProperty("offset", "应用 head_limit 前跳过前 N 行或 N 项，默认 0")
                        .addBooleanProperty("multiline", "启用 ripgrep 多行匹配模式")
                        .required(List.of("pattern"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    /**
     * Grep 工具校验后的模式、路径、输出模式和限制。
     */
    private record GrepInput(
            String pattern,
            String path,
            String glob,
            String outputMode,
            Integer contextBefore,
            Integer contextAfter,
            Integer contextC,
            Integer context,
            boolean showLineNumbers,
            boolean caseInsensitive,
            String type,
            Integer headLimit,
            int offset,
            boolean multiline
    ) {}

    /**
     * ProcessResult 表示操作执行结果。
     */
    private record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) {}

    /**
     * 截断后的结果集合及是否发生截断。
     */
    private record LimitedItems<T>(List<T> items, Integer appliedLimit) {}

    /**
     * 匹配文件路径及其命中的文本行。
     */
    private record FileMatch(Path path, long mtimeMs) {}

    /**
     * 解析后的文件路径、行号和文本内容。
     */
    private record LineParts(String filePath, String rest) {}
}
