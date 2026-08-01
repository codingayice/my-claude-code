package cn.ayice.veyra.tool.permission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 用户批准后生成最小授权建议的工具。它把一次批准转成会话级规则，避免放开过大的访问范围。
 */
public final class PermissionUpdateSuggestions {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String FILE_READ_RULE_TOOL_NAME = "Read";

    private PermissionUpdateSuggestions() {}

    /**
     * 根据已批准调用生成仅在当前会话生效的最小权限更新。
     */
    public static List<PermissionUpdate> generateForSessionAllow(
            ToolExecutionRequest request,
            PermissionContext context
    ) {
        if (context == null) {
            return List.of(PermissionUpdate.addRule(buildAllowRule(request, null)));
        }
        if (isWriteTool(request.name())) {
            return generateWriteSessionUpdates(request, context);
        }
        if (isReadTool(request.name())) {
            PermissionRule rule = buildReadAllowRule(request, context.workingDir());
            return rule == null ? List.of(PermissionUpdate.addRule(buildAllowRule(request, context.workingDir()))) : List.of(PermissionUpdate.addRule(rule));
        }
        return List.of(PermissionUpdate.addRule(buildAllowRule(request, context.workingDir())));
    }

    /**
     * 为写工具生成目标目录和必要读取权限的会话更新。
     */
    private static List<PermissionUpdate> generateWriteSessionUpdates(
            ToolExecutionRequest request,
            PermissionContext context
    ) {
        List<PermissionUpdate> updates = new ArrayList<>();
        updates.add(PermissionUpdate.addRule(buildAllowRule(request, context.workingDir())));

        Path targetPath = extractFilePath(request.arguments(), context.workingDir());
        if (targetPath != null && !context.isWithinAllowedDirectories(targetPath)) {
            Path targetDirectory = targetPath.getParent();
            if (targetDirectory != null) {
                updates.add(PermissionUpdate.addDirectory(targetDirectory));
            }
        }
        return updates;
    }

    /**
     * 根据读取工具参数生成最小范围的会话允许规则。
     */
    private static PermissionRule buildReadAllowRule(ToolExecutionRequest request, Path workingDir) {
        Path path = extractReadPath(request.name(), request.arguments(), workingDir);
        if (path == null) {
            return null;
        }
        Path directory = path;
        if ("Read".equalsIgnoreCase(request.name())) {
            Path parent = path.getParent();
            if (parent != null) {
                directory = parent;
            }
        }
        return PermissionRule.builder()
                .source("session")
                .behavior(PermissionRule.PermissionBehavior.ALLOW)
                .tool(FILE_READ_RULE_TOOL_NAME)
                .content(directoryRule(directory))
                .build();
    }

    /**
     * 根据工具名和参数生成可复用的会话允许规则。
     */
    private static PermissionRule buildAllowRule(ToolExecutionRequest request, Path workingDir) {
        return PermissionRule.builder()
                .source("session")
                .behavior(PermissionRule.PermissionBehavior.ALLOW)
                .tool(request.name())
                .content(extractRuleContent(request.name(), request.arguments(), workingDir))
                .build();
    }

    /**
     * 判断工具名是否属于文件编辑工具。
     */
    private static boolean isEditTool(String toolName) {
        return "Edit".equalsIgnoreCase(toolName) || "file_edit".equalsIgnoreCase(toolName);
    }

    /**
     * 判断工具名是否属于创建或覆盖文件的工具。
     */
    private static boolean isWriteTool(String toolName) {
        return isEditTool(toolName) || "Write".equalsIgnoreCase(toolName) || "file_write".equalsIgnoreCase(toolName);
    }

    /**
     * 判断工具名是否属于文件读取或搜索工具。
     */
    private static boolean isReadTool(String toolName) {
        return "Read".equalsIgnoreCase(toolName) || "Grep".equalsIgnoreCase(toolName) || "Glob".equalsIgnoreCase(toolName);
    }

    /**
     * 从输入中提取文件路径。
     */
    private static Path extractFilePath(String rawArgs, Path workingDir) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(rawArgs);
            String filePath = textField(node, "file_path");
            if (filePath.isBlank()) {
                return null;
            }
            return normalizePath(filePath, workingDir);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /**
     * 从输入中提取读取路径。
     */
    private static Path extractReadPath(String toolName, String rawArgs, Path workingDir) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return workingDir;
        }
        try {
            JsonNode node = MAPPER.readTree(rawArgs);
            if ("Read".equalsIgnoreCase(toolName)) {
                String filePath = textField(node, "file_path");
                return filePath.isBlank() ? null : normalizePath(filePath, workingDir);
            }
            if ("Grep".equalsIgnoreCase(toolName)) {
                String path = textField(node, "path");
                return path.isBlank() ? workingDir : normalizePath(path, workingDir);
            }
            if ("Glob".equalsIgnoreCase(toolName)) {
                String path = textField(node, "path");
                if (!path.isBlank()) {
                    return normalizePath(path, workingDir);
                }
                String pattern = textField(node, "pattern");
                Path base = staticBaseFromGlob(pattern, workingDir);
                return base == null ? workingDir : base;
            }
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
        return null;
    }

    /**
     * 从输入中提取权限规则内容。
     */
    private static String extractRuleContent(String toolName, String rawArgs, Path workingDir) {
        if (rawArgs == null || rawArgs.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(rawArgs);
            if ("bash".equalsIgnoreCase(toolName)) {
                return commandPrefixRule(node.path("command").asText("").trim());
            }
            if ("file".equalsIgnoreCase(toolName)) {
                String action = node.path("action").asText("").trim();
                String path = node.path("path").asText("").trim();
                return action + ":" + path;
            }
            if (isWriteTool(toolName)) {
                String path = textField(node, "file_path");
                return path.isBlank() ? rawArgs.trim() : normalizePath(path, workingDir).toString();
            }
        } catch (Exception ignored) {
        }
        return rawArgs.trim();
    }

    /**
     * 读取 JSON 文本字段；缺失、null 或非文本时返回 null。
     */
    private static String textField(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isTextual() && !value.asText().isBlank()) {
            return value.asText().trim();
        }
        return "";
    }

    /**
     * 将路径规范化为内部统一形式。
     */
    private static Path normalizePath(String path, Path workingDir) {
        if (path.startsWith("~")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        Path normalized = Path.of(path);
        if (!normalized.isAbsolute() && workingDir != null) {
            normalized = workingDir.resolve(normalized);
        }
        return normalized.normalize().toAbsolutePath();
    }

    /**
     * 把规范化目录转换为权限系统使用的目录规则。
     */
    private static String directoryRule(Path directory) {
        return directory.normalize().toAbsolutePath() + "/**";
    }

    /**
     * 从命令中提取稳定前缀并生成 Bash 权限规则。
     */
    private static String commandPrefixRule(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        String[] parts = command.trim().split("\\s+");
        if (parts.length >= 2) {
            return parts[0] + " " + parts[1] + ":*";
        }
        return parts[0] + ":*";
    }

    /**
     * 提取 Glob 首个通配符之前的静态目录并基于工作区解析。
     */
    private static Path staticBaseFromGlob(String pattern, Path workingDir) {
        if (pattern == null || pattern.isBlank()) {
            return workingDir;
        }
        String expanded = pattern.startsWith("~") ? System.getProperty("user.home") + pattern.substring(1) : pattern;
        int wildcard = firstWildcard(expanded);
        String prefix = wildcard < 0 ? expanded : expanded.substring(0, wildcard);
        int slash = Math.max(prefix.lastIndexOf('/'), prefix.lastIndexOf('\\'));
        if (slash >= 0) {
            prefix = prefix.substring(0, slash);
        } else {
            prefix = "";
        }
        if (prefix.isBlank()) {
            return workingDir;
        }
        return normalizePath(prefix, workingDir);
    }

    /**
     * 返回 Glob 中首个通配符的位置；不存在时返回 -1。
     */
    private static int firstWildcard(String value) {
        int out = -1;
        for (char ch : new char[]{'*', '?', '[', '{'}) {
            int idx = value.indexOf(ch);
            if (idx >= 0 && (out < 0 || idx < out)) {
                out = idx;
            }
        }
        return out;
    }
}
