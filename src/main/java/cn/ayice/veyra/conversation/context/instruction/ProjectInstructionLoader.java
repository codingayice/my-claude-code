package cn.ayice.veyra.conversation.context.instruction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 项目级指令记忆加载器。它读取 AGENTS.md 这类持久指令文件，并准备注入系统提示词。
 */
public class ProjectInstructionLoader {

    private static final int MAX_INCLUDE_DEPTH = 3;
    private static final Set<String> ALLOWED_INCLUDE_EXTENSIONS = Set.of(
            ".md", ".txt", ".json", ".yaml", ".yml", ".toml"
    );

    private final Path homeDir;
    private final Path workspaceDir;

    public ProjectInstructionLoader(Path homeDir, Path workspaceDir) {
        this.homeDir = homeDir;
        this.workspaceDir = workspaceDir;
    }

    /**
     * 使用默认用户目录和给定工作区创建加载器。
     */
    public static ProjectInstructionLoader defaults(Path workspaceDir) {
        return new ProjectInstructionLoader(Path.of(System.getProperty("user.home")), workspaceDir);
    }

    /**
     * 按优先级加载当前工作区可用的指令或记忆内容。
     */
    public String load() {
        List<Path> files = instructionFiles();
        Set<Path> seen = new HashSet<>();
        List<String> sections = new ArrayList<>();
        for (Path file : files) {
            String content = loadFile(file, 0, seen);
            if (content != null && !content.isBlank()) {
                sections.add("### %s\n\n%s".formatted(
                        file.toAbsolutePath().normalize(),
                        content.trim()
                ));
            }
        }
        return String.join("\n\n", sections).trim();
    }

    /**
     * 按用户级到项目级的覆盖顺序返回候选指令文件。
     */
    private List<Path> instructionFiles() {
        List<Path> files = new ArrayList<>();
        files.add(homeDir.resolve(".mycc").resolve("CLAUDE.md"));
        files.add(workspaceDir.resolve("CLAUDE.md"));
        files.add(workspaceDir.resolve(".claude").resolve("CLAUDE.md"));
        files.addAll(ruleFiles());
        files.add(workspaceDir.resolve("CLAUDE.local.md"));
        return files;
    }

    /**
     * 返回当前工作区可参与权限判断的规则文件集合。
     */
    private List<Path> ruleFiles() {
        Path rulesDir = workspaceDir.resolve(".claude").resolve("rules");
        if (!Files.isDirectory(rulesDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(rulesDir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".md"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 从持久化介质加载文件。
     */
    private String loadFile(Path file, int depth, Set<Path> seen) {
        if (depth > MAX_INCLUDE_DEPTH || !Files.isRegularFile(file)) {
            return null;
        }
        Path normalized = file.toAbsolutePath().normalize();
        if (!seen.add(normalized)) {
            return null;
        }
        try {
            FrontmatterDocument document = parseFrontmatter(Files.readString(normalized));
            if (!matchesPaths(document.paths())) {
                return null;
            }
            String raw = stripHtmlComments(document.content());
            List<String> lines = new ArrayList<>();
            for (String line : raw.split("\\R")) {
                String trimmed = line.trim();
                String includeTarget = includeTarget(trimmed);
                if (includeTarget != null) {
                    Path include = resolveInclude(normalized, trimmed, includeTarget);
                    if (isAllowedInclude(include)) {
                        String included = loadFile(include, depth + 1, seen);
                        if (included != null && !included.isBlank()) {
                            lines.add(included);
                        }
                    }
                } else {
                    lines.add(line);
                }
            }
            return String.join("\n", lines).trim();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * 解析并校验包含指令。
     */
    private Path resolveInclude(Path source, String directive, String target) {
        if (directive.startsWith("@include ")) {
            return source.getParent().resolve(target).normalize();
        }
        return workspaceDir.resolve(target).normalize();
    }

    /**
     * 判断当前工作区相对路径是否命中任一 Frontmatter 路径约束。
     */
    private boolean matchesPaths(List<String> patterns) {
        if (patterns.isEmpty()) {
            return true;
        }
        Path root = workspaceDir.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(path -> root.relativize(path.toAbsolutePath().normalize()))
                    .anyMatch(relative -> patterns.stream().anyMatch(pattern -> matchesGlob(relative, pattern)));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 使用项目路径语义判断相对路径是否匹配给定 Glob。
     */
    private boolean matchesGlob(Path relative, String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        String normalizedPattern = pattern.trim().replace("\\", "/");
        PathMatcher matcher = workspaceDir.getFileSystem().getPathMatcher("glob:" + normalizedPattern);
        if (matcher.matches(relative)) {
            return true;
        }
        if (!java.io.File.separator.equals("/")) {
            PathMatcher platformMatcher = workspaceDir.getFileSystem()
                    .getPathMatcher("glob:" + normalizedPattern.replace("/", java.io.File.separator));
            return platformMatcher.matches(relative);
        }
        return false;
    }

    /**
     * 判断 include 目标是否位于允许读取的用户目录或工作区内。
     */
    private static boolean isAllowedInclude(Path path) {
        String name = path.getFileName().toString().toLowerCase();
        return ALLOWED_INCLUDE_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    /**
     * 从 include 指令中提取并去除引号后的目标路径。
     */
    private static String includeTarget(String trimmed) {
        if (trimmed.startsWith("@include ")) {
            String target = trimmed.substring("@include ".length()).trim();
            return target.isBlank() ? null : target;
        }
        if (trimmed.startsWith("@") && !trimmed.contains(" ")) {
            String target = trimmed.substring(1).trim();
            return target.isBlank() ? null : target;
        }
        return null;
    }

    /**
     * 解析指令文件的 Frontmatter 路径约束和正文；没有头部时保留完整正文。
     */
    private static FrontmatterDocument parseFrontmatter(String content) {
        if (content == null || !content.startsWith("---")) {
            return new FrontmatterDocument(List.of(), content == null ? "" : content);
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return new FrontmatterDocument(List.of(), content);
        }
        String frontmatter = content.substring(3, end);
        int after = content.indexOf('\n', end + 1);
        String body = after < 0 ? "" : content.substring(after + 1);
        return new FrontmatterDocument(parsePaths(frontmatter), body);
    }

    /**
     * 解析输入并返回路径集合。
     */
    private static List<String> parsePaths(String frontmatter) {
        List<String> paths = new ArrayList<>();
        boolean readingPaths = false;
        for (String line : frontmatter.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals("paths:")) {
                readingPaths = true;
                continue;
            }
            if (readingPaths && trimmed.startsWith("- ")) {
                String path = trimmed.substring(2).trim();
                if (!path.isBlank()) {
                    paths.add(unquote(path));
                }
                continue;
            }
            if (readingPaths && !trimmed.isBlank() && !line.startsWith(" ")) {
                readingPaths = false;
            }
        }
        return paths;
    }

    /**
     * 去除一对匹配的单引号或双引号，其他文本保持不变。
     */
    private static String unquote(String value) {
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 移除指令文件中的 HTML 注释，避免隐藏内容进入系统提示词。
     */
    private static String stripHtmlComments(String content) {
        return content == null ? "" : content.replaceAll("(?s)<!--.*?-->", "");
    }

    /**
     * 解析后的 Frontmatter 路径约束和正文内容。
     */
    private record FrontmatterDocument(List<String> paths, String content) {
    }
}
