package cn.ayice.veyra.tooling.permission;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;


/**
 * 一次 agent 运行中的不可变权限状态。它记录工作目录、权限模式、允许目录和临时会话规则。
 */
public class PermissionContext {

    private final PermissionMode mode;
    private final List<PermissionRule> rules;
    private final List<Path> allowedDirectories;
    private final Path workingDir;

    private PermissionContext(Builder builder) {
        this.mode = builder.mode;
        this.allowedDirectories = new ArrayList<>(builder.allowedDirectories);
        this.workingDir = builder.workingDir;
        this.rules = new ArrayList<>(builder.rules);
    }

    /**
     * 创建用于逐步填写字段的空构建器。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 创建用于逐步填写字段的空构建器。
     */
    private static Builder builder(PermissionContext context) {
        return new Builder(context);
    }

    /**
     * Builder 按步骤构建目标对象。
     */
    public static class Builder {
        private PermissionMode mode;
        private List<PermissionRule> rules = new ArrayList<>();
        private List<Path> allowedDirectories = new ArrayList<>();
        private Path workingDir;

        public Builder() {}

        private Builder(PermissionContext context) {
            this.mode = context.mode;
            this.rules = new ArrayList<>(context.rules);
            this.allowedDirectories = new ArrayList<>(context.allowedDirectories);
            this.workingDir = context.workingDir;
        }

        /**
         * 设置或返回当前权限模式。
         */
        public Builder mode(PermissionMode mode) {
            this.mode = mode;
            return this;
        }

        /**
         * 设置或返回允许工具访问的规范化目录集合。
         */
        public Builder allowedDirectories(List<Path> directories) {
            this.allowedDirectories = new ArrayList<>();
            if (directories != null) {
                for (Path directory : directories) {
                    addAllowedDirectory(directory);
                }
            }
            return this;
        }

        /**
         * 将给定项加入允许项目录。
         */
        public Builder addAllowedDirectory(Path directory) {
            if (directory != null) {
                Path normalized = normalizeAbsolute(directory);
                if (!containsPath(this.allowedDirectories, normalized)) {
                    this.allowedDirectories.add(normalized);
                }
            }
            return this;
        }

        /**
         * 设置或返回权限规则解析使用的工作目录。
         */
        public Builder workingDir(Path path) {
            this.workingDir = normalizeAbsolute(path);
            return this;
        }

        /**
         * 将给定项加入权限规则。
         */
        public Builder addRule(PermissionRule rule) {
            this.rules.add(rule);
            return this;
        }

        /**
         * 根据当前输入构建目标对象。
         */
        public PermissionContext build() {
            return new PermissionContext(this);
        }
    }

    /**
     * 设置或返回当前权限模式。
     */
    public PermissionMode mode() {
        return mode;
    }

    /**
     * 设置或返回允许工具访问的规范化目录集合。
     */
    public List<Path> allowedDirectories() {
        return Collections.unmodifiableList(allowedDirectories);
    }

    /**
     * 设置或返回权限规则解析使用的工作目录。
     */
    public Path workingDir() {
        return workingDir;
    }

    /**
     * 返回按声明顺序保存的不可变权限规则集合。
     */
    public List<PermissionRule> rules() {
        return Collections.unmodifiableList(rules);
    }

    /**
     * 检查路径是否在允许的目录内
     */
    public boolean isWithinAllowedDirectories(Path path) {
        for (Path allowedDir : allowedDirectories) {
            if (isWithinDirectory(path, allowedDir)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 复制当前对象，并将权限模式替换为给定值。
     */
    public PermissionContext withMode(PermissionMode mode) {
        return builder(this).mode(mode).build();
    }

    /**
     * 复制当前对象，并将允许项目录替换为给定值。
     */
    public PermissionContext withAllowedDirectory(Path directory) {
        return builder(this).addAllowedDirectory(directory).build();
    }

    /**
     * 复制当前对象，并将工作目录替换为给定值。
     */
    public PermissionContext withWorkingDirectory(Path directory) {
        return builder(this)
                .workingDir(directory)
                .allowedDirectories(directory == null ? List.of() : List.of(directory))
                .build();
    }

    /**
     * 复制当前对象，并将权限规则替换为给定值。
     */
    public PermissionContext withRule(PermissionRule rule) {
        return builder(this).addRule(rule).build();
    }

    /**
     * 按工具名查找不限制具体参数的权限规则；未命中时返回 null。
     */
    public PermissionRule findToolWideRule (String toolName, PermissionRule.PermissionBehavior behavior) {
        for (PermissionRule rule : rules) {
            if (rule.ruleBehavior() == behavior
                    && toolName != null
                    && toolName.equalsIgnoreCase(rule.toolName())
                    && rule.isToolWideRule()) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 查找匹配工具名和内容的规则
     */
    public PermissionRule findRule(String toolName, String content, PermissionRule.PermissionBehavior behavior) {
        for (PermissionRule rule : rules) {
            if (rule.ruleBehavior() == behavior
                    && toolName != null
                    && toolName.equalsIgnoreCase(rule.toolName())
                    && content != null
                    && ruleContentMatches(rule.ruleContent(), content)) {
                return rule;
            }
        }
        return null;
    }

    /**
     * 判断规则内容是否精确匹配或覆盖给定工具参数。
     */
    private static boolean ruleContentMatches(String ruleContent, String content) {
        if (ruleContent == null || ruleContent.isBlank()) {
            return true;
        }
        if (content.equals(ruleContent)) {
            return true;
        }
        if (ruleContent.endsWith(":*")) {
            String prefix = ruleContent.substring(0, ruleContent.length() - 2).trim();
            return content.equals(prefix) || content.startsWith(prefix + " ");
        }
        if (ruleContent.endsWith("/**") || ruleContent.endsWith("\\**")) {
            String base = ruleContent.substring(0, ruleContent.length() - 3);
            return isWithinRuleDirectory(content, base);
        }
        if (ruleContent.contains("*")) {
            return globToRegex(ruleContent).matcher(content.replace('\\', '/')).matches();
        }
        return false;
    }

    /**
     * 判断路径参数是否位于目录型权限规则覆盖范围内。
     */
    private static boolean isWithinRuleDirectory(String content, String base) {
        Path contentPath = normalizeAbsolute(Path.of(content));
        Path basePath = normalizeAbsolute(Path.of(base));
        return contentPath != null && basePath != null && contentPath.startsWith(basePath);
    }

    /**
     * 将权限规则中的 Glob 表达式转换为完整匹配的正则表达式。
     */
    private static Pattern globToRegex(String glob) {
        String normalized = glob.replace('\\', '/');
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '*') {
                boolean doublestar = i + 1 < normalized.length() && normalized.charAt(i + 1) == '*';
                if (doublestar) {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (".()[]{}+$^|?\\".indexOf(ch) >= 0) {
                regex.append('\\').append(ch);
            } else {
                regex.append(ch);
            }
        }
        regex.append("$");
        return Pattern.compile(regex.toString());
    }

    /**
     * 通过规范化绝对路径判断候选路径是否位于目录内。
     */
    private static boolean isWithinDirectory(Path path, Path directory) {
        Path normalizedPath = normalizeAbsolute(path);
        Path normalizedDirectory = normalizeAbsolute(directory);
        return normalizedPath != null
                && normalizedDirectory != null
                && normalizedPath.startsWith(normalizedDirectory);
    }

    /**
     * 判断目录集合是否已包含规范化后的候选路径。
     */
    private static boolean containsPath(List<Path> paths, Path candidate) {
        Path normalizedCandidate = normalizeAbsolute(candidate);
        if (normalizedCandidate == null) {
            return false;
        }
        for (Path path : paths) {
            Path normalizedPath = normalizeAbsolute(path);
            if (normalizedCandidate.equals(normalizedPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将绝对规范化为内部统一形式。
     */
    private static Path normalizeAbsolute(Path path) {
        return path == null ? null : path.normalize().toAbsolutePath();
    }
}
