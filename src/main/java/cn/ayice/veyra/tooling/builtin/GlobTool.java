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

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按 glob 模式查找文件名的工具。它帮助模型发现相关文件，而不需要手工遍历目录。
 */
public class GlobTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_RESULTS = 100;
    private static final Pattern GLOB_SPECIAL_CHARS = Pattern.compile("[*?\\[{]");
    private static final Pattern WINDOWS_ABSOLUTE_PATTERN = Pattern.compile("^[A-Za-z]:[\\\\/].*");

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "Glob";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return "- 快速文件名模式匹配工具，可用于任意规模代码库\n" +
                "- 支持 \"**/*.js\" 或 \"src/**/*.ts\" 这类 glob 模式\n" +
                "- 返回按修改时间排序的匹配文件路径\n" +
                "- path 使用相对路径时只基于当前 workingDir；如果要搜索其他目录，必须使用绝对路径\n" +
                "- 当你需要按文件名模式查找文件时使用此工具";
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
            GlobInput input = parseAndValidateInput(arguments);
            SearchSpec spec = resolveSearchSpec(input, context == null ? null : context.workingDir());
            if (spec.searchDir() == null) {
                return PermissionDecision.deny("搜索路径无效");
            }
            return PermissionSupport.checkReadPathPermission(
                    name(),
                    spec.searchDir(),
                    context,
                    "查找文件: " + spec.searchDir()
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
            GlobInput input = parseAndValidateInput(arguments);
            SearchSpec spec = resolveSearchSpec(input, context == null ? null : context.workingDir());
            if (spec.searchDir() == null) {
                return ValidationResult.invalid("搜索路径无效");
            }
            if (PermissionSupport.isUncPath(input.path()) || PermissionSupport.isUncPath(spec.searchDir().toString())) {
                return ValidationResult.ok();
            }
            if (!Files.exists(spec.searchDir())) {
                return ValidationResult.invalid("目录不存在: " + displayPath(input, spec));
            }
            if (!Files.isDirectory(spec.searchDir())) {
                return ValidationResult.invalid("路径不是目录: " + displayPath(input, spec));
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
            GlobInput input = parseAndValidateInput(arguments);
            Path workingDir = context == null ? null : context.workingDir();
            SearchSpec spec = resolveSearchSpec(input, workingDir);
            if (spec.searchDir() == null) {
                return ToolResult.error("搜索路径无效");
            }

            if (!Files.exists(spec.searchDir())) {
                return ToolResult.error("目录不存在: " + displayPath(input, spec));
            }
            if (!Files.isDirectory(spec.searchDir())) {
                return ToolResult.error("路径不是目录: " + displayPath(input, spec));
            }

            List<GlobMatch> matches = findMatches(spec.searchDir(), spec.pattern());
            matches.sort(Comparator.comparingLong(GlobMatch::mtimeMs).reversed()
                    .thenComparing(match -> match.path().toString()));

            boolean truncated = matches.size() > MAX_RESULTS;
            List<GlobMatch> limitedMatches = truncated ? matches.subList(0, MAX_RESULTS) : matches;
            if (limitedMatches.isEmpty()) {
                return ToolResult.success("未找到文件");
            }

            Path outputBase = workingDir == null ? Path.of("").toAbsolutePath().normalize() : workingDir.normalize().toAbsolutePath();
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < limitedMatches.size(); i++) {
                if (i > 0) {
                    result.append('\n');
                }
                result.append(toRelativePath(limitedMatches.get(i).path(), outputBase));
            }
            if (truncated) {
                result.append("\n(结果已截断。请考虑使用更具体的路径或 pattern。)");
            }
            return ToolResult.success(result.toString());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("查找文件失败: " + e.getMessage());
        }
    }

    /**
     * 解析工具 JSON 参数并完成字段、类型和路径边界校验。
     */
    private GlobInput parseAndValidateInput(String arguments) throws IOException {
        JsonNode node = MAPPER.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!node.isObject()) {
            throw new IllegalArgumentException("参数必须是 JSON 对象");
        }

        String pattern = node.path("pattern").asText("");
        if (pattern.isBlank()) {
            throw new IllegalArgumentException("pattern 是必填字段");
        }

        String path = null;
        if (node.has("path") && !node.get("path").isNull()) {
            JsonNode pathNode = node.get("path");
            if (!pathNode.isTextual()) {
                throw new IllegalArgumentException("path 必须是字符串");
            }
            path = pathNode.asText();
            if (path.isBlank()) {
                path = null;
            } else if (PermissionSupport.containsPathTraversal(path)) {
                throw new IllegalArgumentException("检测到路径穿越: " + path);
            }
        }

        return new GlobInput(pattern, path);
    }

    /**
     * 从 Glob 输入中解析静态搜索根和剩余匹配模式，并校验路径边界。
     */
    private SearchSpec resolveSearchSpec(GlobInput input, Path workingDir) {
        Path baseDir = workingDir == null ? Path.of("").toAbsolutePath().normalize() : workingDir.normalize().toAbsolutePath();
        String pattern = expandHome(input.pattern());

        if (isAbsolutePattern(pattern)) {
            GlobBase globBase = extractGlobBaseDirectory(pattern);
            Path searchDir = normalizePath(globBase.baseDir(), baseDir);
            return new SearchSpec(searchDir, normalizeGlobPattern(globBase.relativePattern()));
        }

        Path searchDir = input.path() == null ? baseDir : normalizePath(input.path(), baseDir);
        return new SearchSpec(searchDir, normalizeGlobPattern(input.pattern()));
    }

    /**
     * 从输入中提取Glob 模式基础目录。
     */
    private GlobBase extractGlobBaseDirectory(String pattern) {
        java.util.regex.Matcher matcher = GLOB_SPECIAL_CHARS.matcher(pattern);
        if (!matcher.find()) {
            int lastSeparator = lastSeparatorIndex(pattern);
            if (lastSeparator < 0) {
                return new GlobBase(".", pattern);
            }
            return new GlobBase(pattern.substring(0, lastSeparator), pattern.substring(lastSeparator + 1));
        }

        String staticPrefix = pattern.substring(0, matcher.start());
        int lastSeparator = lastSeparatorIndex(staticPrefix);
        if (lastSeparator < 0) {
            return new GlobBase("", pattern);
        }

        String baseDir = staticPrefix.substring(0, lastSeparator);
        String relativePattern = pattern.substring(lastSeparator + 1);
        if (baseDir.isEmpty() && lastSeparator == 0) {
            baseDir = FileSystems.getDefault().getSeparator();
        }
        if (baseDir.matches("^[A-Za-z]:$")) {
            baseDir += FileSystems.getDefault().getSeparator();
        }
        return new GlobBase(baseDir, relativePattern);
    }

    /**
     * 查找匹配结果；不存在时返回空结果。
     */
    private List<GlobMatch> findMatches(Path searchDir, String globPattern) throws IOException {
        Pattern compiledPattern = compileGlob(globPattern);
        boolean basenameOnly = !globPattern.contains("/");
        List<GlobMatch> matches = new ArrayList<>();

        Files.walkFileTree(searchDir, new SimpleFileVisitor<>() {
            /**
             * {@inheritDoc}
             */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile() && matchesPattern(searchDir, file, compiledPattern, basenameOnly)) {
                    matches.add(new GlobMatch(file.normalize().toAbsolutePath(), attrs.lastModifiedTime().toMillis()));
                }
                return FileVisitResult.CONTINUE;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }
        });

        return matches;
    }

    /**
     * 判断候选文件的相对路径或文件名是否匹配编译后的 Glob。
     */
    private boolean matchesPattern(Path searchDir, Path file, Pattern pattern, boolean basenameOnly) {
        String relativePath = normalizePathForGlob(searchDir.relativize(file).toString());
        if (pattern.matcher(relativePath).matches()) {
            return true;
        }
        return basenameOnly && pattern.matcher(normalizePathForGlob(file.getFileName().toString())).matches();
    }

    /**
     * 将 Glob 语法编译为兼容跨平台路径分隔符的正则表达式。
     */
    private Pattern compileGlob(String globPattern) {
        return Pattern.compile(globToRegex(globPattern));
    }

    /**
     * 将权限规则中的 Glob 表达式转换为完整匹配的正则表达式。
     */
    private String globToRegex(String globPattern) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < globPattern.length(); i++) {
            char ch = globPattern.charAt(i);

            if (ch == '*') {
                boolean isDoubleStar = i + 1 < globPattern.length() && globPattern.charAt(i + 1) == '*';
                if (isDoubleStar) {
                    boolean followedBySlash = i + 2 < globPattern.length() && globPattern.charAt(i + 2) == '/';
                    if (followedBySlash) {
                        regex.append("(?:.*/)?");
                        i += 2;
                    } else {
                        regex.append(".*");
                        i++;
                    }
                } else {
                    regex.append("[^/]*");
                }
                continue;
            }

            if (ch == '?') {
                regex.append("[^/]");
                continue;
            }

            if (ch == '[') {
                int end = globPattern.indexOf(']', i + 1);
                if (end > i) {
                    regex.append(globPattern, i, end + 1);
                    i = end;
                } else {
                    regex.append("\\[");
                }
                continue;
            }

            if (ch == '{') {
                int end = globPattern.indexOf('}', i + 1);
                if (end > i) {
                    regex.append("(?:");
                    String body = globPattern.substring(i + 1, end);
                    String[] parts = body.split(",", -1);
                    for (int partIndex = 0; partIndex < parts.length; partIndex++) {
                        if (partIndex > 0) {
                            regex.append('|');
                        }
                        regex.append(Pattern.quote(parts[partIndex]));
                    }
                    regex.append(')');
                    i = end;
                } else {
                    regex.append("\\{");
                }
                continue;
            }

            if ("\\.()|+^$@%".indexOf(ch) >= 0) {
                regex.append('\\');
            }
            regex.append(ch);
        }
        regex.append('$');
        return regex.toString();
    }

    /**
     * 将Glob 模式匹配模式规范化为内部统一形式。
     */
    private String normalizeGlobPattern(String pattern) {
        String normalized = normalizePathForGlob(pattern);
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    /**
     * 将平台路径分隔符统一为正斜杠，供 Glob 正则匹配。
     */
    private String normalizePathForGlob(String path) {
        return path.replace('\\', '/');
    }

    /**
     * 判断 Glob 是否以 Unix 根目录、UNC 或 Windows 盘符开头。
     */
    private boolean isAbsolutePattern(String pattern) {
        String expanded = expandHome(pattern);
        return expanded.startsWith("/")
                || expanded.startsWith("\\\\")
                || expanded.startsWith("//")
                || WINDOWS_ABSOLUTE_PATTERN.matcher(expanded).matches();
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
     * 将路径规范化为内部统一形式。
     */
    private Path normalizePath(String path, Path workingDir) {
        try {
            if (path == null || path.isBlank()) {
                return workingDir == null ? Path.of("").toAbsolutePath().normalize() : workingDir.normalize().toAbsolutePath();
            }

            String expanded = expandHome(path);
            Path normalizedPath = Path.of(expanded);
            if (!normalizedPath.isAbsolute()) {
                Path base = workingDir == null ? Path.of("").toAbsolutePath() : workingDir;
                normalizedPath = base.resolve(normalizedPath);
            }
            return normalizedPath.normalize().toAbsolutePath();
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /**
     * 返回路径中最后一个正斜杠或反斜杠的位置。
     */
    private int lastSeparatorIndex(String value) {
        return Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
    }

    /**
     * 根据搜索根和用户输入选择稳定、可读的结果展示路径。
     */
    private String displayPath(GlobInput input, SearchSpec spec) {
        if (input.path() != null) {
            return input.path();
        }
        return spec.searchDir().toString();
    }

    /**
     * 将绝对路径转换为相对工作目录、使用正斜杠的展示路径。
     */
    private String toRelativePath(Path file, Path workingDir) {
        Path normalizedFile = file.normalize().toAbsolutePath();
        Path normalizedWorkingDir = workingDir.normalize().toAbsolutePath();
        if (normalizedFile.startsWith(normalizedWorkingDir)) {
            return normalizedWorkingDir.relativize(normalizedFile).toString();
        }
        return normalizedFile.toString();
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
                        .addStringProperty("pattern", "用于匹配文件的 glob 模式")
                        .addStringProperty("path", "要搜索的目录；不指定时使用当前 workingDir。相对路径只基于当前 workingDir；其他目录必须使用绝对路径")
                        .required(List.of("pattern"))
                        .additionalProperties(true)
                        .build())
                .build();
    }

    /**
     * Glob 工具校验后的模式、根路径和结果限制。
     */
    private record GlobInput(String pattern, String path) {}

    /**
     * 一次文件搜索使用的根目录和相对 Glob 模式。
     */
    private record SearchSpec(Path searchDir, String pattern) {}

    /**
     * 从 Glob 模式提取出的静态搜索根和剩余模式。
     */
    private record GlobBase(String baseDir, String relativePattern) {}

    /**
     * 文件搜索结果及其最后修改时间。
     */
    private record GlobMatch(Path path, long mtimeMs) {}
}
