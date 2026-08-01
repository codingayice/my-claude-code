package cn.ayice.veyra.tool.permission;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 权限系统共享的路径和规则辅助方法。PermissionChecker 和授权更新逻辑通过它保持路径判断一致。
 */
public final class PermissionSupport {

    private static final String FILE_READ_RULE_TOOL_NAME = "Read";
    private static final Pattern PATH_TRAVERSAL_PATTERN = Pattern.compile("(?:^|[\\\\/])\\.\\.(?:[\\\\/]|$)");
    private static final Pattern SHORT_NAME_PATTERN = Pattern.compile("(?i)(?:^|[\\\\/])[^\\\\/]*~\\d(?:[\\\\/.]|$)");
    private static final Set<String> DOS_DEVICE_NAMES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    );

    private PermissionSupport() {}

    /**
     * 校验读取路径边界，并结合规则和权限模式返回执行决定。
     */
    public static PermissionDecision checkReadPathPermission(
            String toolName,
            Path normalizedPath,
            PermissionContext context,
            String actionDescription
    ) {
        if (normalizedPath == null) {
            return PermissionDecision.deny("Invalid path");
        }

        String pathStr = normalizedPath.toString();
        if (isUncPath(pathStr)) {
            return PermissionDecision.ask(actionDescription + " (UNC path requires confirmation)");
        }
        if (hasSuspiciousWindowsPathPattern(pathStr)) {
            return PermissionDecision.ask(actionDescription + " (suspicious Windows path requires confirmation)");
        }

        String ruleToolName = readRuleToolName(toolName);

        PermissionRule denyRule = context == null ? null : context.findRule(ruleToolName, pathStr, PermissionRule.PermissionBehavior.DENY);
        if (denyRule != null) {
            return PermissionDecision.deny("Path matched deny rule", denyRule);
        }

        PermissionRule askRule = context == null ? null : context.findRule(ruleToolName, pathStr, PermissionRule.PermissionBehavior.ASK);
        if (askRule != null) {
            return PermissionDecision.ask(actionDescription, askRule);
        }

        PermissionRule allowRule = context == null ? null : context.findRule(ruleToolName, pathStr, PermissionRule.PermissionBehavior.ALLOW);
        if (allowRule != null) {
            return PermissionDecision.allow("Path matched allow rule", allowRule);
        }

        if (context != null
                && context.mode() == PermissionMode.PROJECT_AUTO
                && context.isWithinAllowedDirectories(normalizedPath)) {
            return PermissionDecision.allow("Project auto mode allows project read");
        }

        if (context != null && context.mode() == PermissionMode.AUTO_APPROVE) {
            return PermissionDecision.allow("Auto approve mode allows read");
        }

        return PermissionDecision.ask(actionDescription);
    }

    /**
     * 读取权限规则工具名称。
     */
    private static String readRuleToolName(String toolName) {
        if ("Grep".equalsIgnoreCase(toolName) || "Glob".equalsIgnoreCase(toolName)) {
            return FILE_READ_RULE_TOOL_NAME;
        }
        return toolName;
    }

    /**
     * 校验写入路径边界，并结合规则和权限模式返回执行决定。
     */
    public static PermissionDecision checkWritePathPermission(
            String toolName,
            Path normalizedPath,
            PermissionContext context,
            String actionDescription
    ) {
        if (normalizedPath == null) {
            return PermissionDecision.deny("Invalid path");
        }

        String pathStr = normalizedPath.toString();
        if (isUncPath(pathStr)) {
            return PermissionDecision.ask(actionDescription + " (UNC path requires confirmation)");
        }
        if (hasSuspiciousWindowsPathPattern(pathStr)) {
            return PermissionDecision.ask(actionDescription + " (suspicious Windows path requires confirmation)");
        }

        PermissionRule denyRule = context == null ? null : context.findRule(toolName, pathStr, PermissionRule.PermissionBehavior.DENY);
        if (denyRule != null) {
            return PermissionDecision.deny("Path matched deny rule", denyRule);
        }

        PermissionRule askRule = context == null ? null : context.findRule(toolName, pathStr, PermissionRule.PermissionBehavior.ASK);
        if (askRule != null) {
            return PermissionDecision.ask(actionDescription, askRule);
        }

        PermissionRule allowRule = context == null ? null : context.findRule(toolName, pathStr, PermissionRule.PermissionBehavior.ALLOW);
        if (allowRule != null) {
            return PermissionDecision.allow("Path matched allow rule", allowRule);
        }

        if (context != null
                && context.mode() == PermissionMode.PROJECT_AUTO
                && context.isWithinAllowedDirectories(normalizedPath)) {
            return PermissionDecision.allow("Project auto mode allows project write");
        }

        if (context != null && context.mode() == PermissionMode.AUTO_APPROVE) {
            return PermissionDecision.allow("Auto approve mode allows write");
        }

        return PermissionDecision.ask(actionDescription);
    }

    /**
     * 判断路径是否包含可逃逸当前目录的父级遍历片段。
     */
    public static boolean containsPathTraversal(String path) {
        return path != null && PATH_TRAVERSAL_PATTERN.matcher(path).find();
    }

    /**
     * 判断路径是否为 UNC 网络共享路径。
     */
    public static boolean isUncPath(String path) {
        return path != null
                && (path.startsWith("\\\\")
                || path.startsWith("//"));
    }

    /**
     * 判断路径是否包含设备路径、数据流或保留设备名。
     */
    public static boolean hasSuspiciousWindowsPathPattern(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }

        String normalized = path.replace('/', '\\');
        if (normalized.startsWith("\\\\?\\") || normalized.startsWith("\\\\.\\")
                || path.startsWith("//?/") || path.startsWith("//./")) {
            return true;
        }
        if (hasAlternateDataStream(path)) {
            return true;
        }
        if (SHORT_NAME_PATTERN.matcher(path).find()) {
            return true;
        }
        if (path.contains("...")) {
            return true;
        }

        for (String segment : path.split("[\\\\/]+")) {
            if (segment.isBlank() || segment.matches("^[A-Za-z]:$")) {
                continue;
            }
            if (segment.endsWith(".") || segment.endsWith(" ")) {
                return true;
            }
            if (isDosDeviceName(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断 Windows 路径是否使用 NTFS 备用数据流语法。
     */
    private static boolean hasAlternateDataStream(String path) {
        int firstColon = path.indexOf(':');
        if (firstColon < 0) {
            return false;
        }
        boolean driveColon = firstColon == 1 && Character.isLetter(path.charAt(0));
        if (!driveColon) {
            return true;
        }
        return path.indexOf(':', firstColon + 1) >= 0;
    }

    /**
     * 判断路径段是否为 Windows 保留的 DOS 设备名。
     */
    private static boolean isDosDeviceName(String segment) {
        String clean = segment;
        while (clean.endsWith(".") || clean.endsWith(" ")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        int dot = clean.indexOf('.');
        if (dot >= 0) {
            clean = clean.substring(0, dot);
        }
        return DOS_DEVICE_NAMES.contains(clean.toUpperCase(Locale.ROOT));
    }
}
