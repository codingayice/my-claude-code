package cn.ayice.veyra.tool.builtin;

import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolResult;
import cn.ayice.veyra.tool.ValidationResult;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionRule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 执行 shell 命令的工具。它支持普通模式和只读模式，并依赖权限系统控制风险。
 */
public class BashTool extends BaseTool {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final long TIMEOUT_SECONDS = 60;
    private static final Pattern WRITE_OPERATOR_PATTERN = Pattern.compile("(?s)(>>|>|<\\s*<|\\|)");
    private static final Pattern COMMAND_SEPARATOR_PATTERN = Pattern.compile("(?s)(;|&&|\\|\\|)");

    private final boolean readOnly;

    public BashTool() {
        this(false);
    }

    public BashTool(boolean readOnly) {
        this.readOnly = readOnly;
    }

    /**
     * 返回该工具是否只执行不会修改外部状态的操作。
     */
    public boolean isReadOnly() {
        return readOnly;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String name() {
        return "bash";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String description() {
        return readOnly
                ? "Read-only Bash tool. Only a small allowlist of diagnostic commands is permitted."
                : "Bash tool for executing shell commands. Commands may change system state and require permission.";
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Category category() {
        return Category.SHELL;
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
        return readOnly ? RiskLevel.CAUTION : RiskLevel.DANGEROUS;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
        try {
            BashInput input = parseInput(arguments);
            if (readOnly) {
                return isReadOnlyCommand(input.command())
                        ? PermissionDecision.allow("Read-only Bash command is allowed")
                        : PermissionDecision.deny("Read-only Bash does not allow this command: " + input.command());
            }
            PermissionDecision ruleDecision = checkCommandRules(input.command(), context);
            if (ruleDecision != null) {
                return ruleDecision;
            }
            if (context != null && context.mode() != null && context.mode().allowsToolExecutionByDefault()) {
                return PermissionDecision.allow("Current permission mode allows Bash");
            }
            return PermissionDecision.ask("Bash command requires confirmation");
        } catch (Exception e) {
            return PermissionDecision.deny("Invalid arguments: " + e.getMessage());
        }
    }

    /**
     * 按命令前缀权限规则计算 Bash 调用的执行决定。
     */
    private PermissionDecision checkCommandRules(String command, PermissionContext context) {
        if (context == null) {
            return null;
        }
        PermissionRule denyRule = context.findRule(name(), command, PermissionRule.PermissionBehavior.DENY);
        if (denyRule != null) {
            return PermissionDecision.deny("Bash command matched deny rule", denyRule);
        }
        PermissionRule askRule = context.findRule(name(), command, PermissionRule.PermissionBehavior.ASK);
        if (askRule != null) {
            return PermissionDecision.ask("Bash command matched ask rule", askRule);
        }
        PermissionRule allowRule = context.findRule(name(), command, PermissionRule.PermissionBehavior.ALLOW);
        if (allowRule != null) {
            return PermissionDecision.allow("Bash command matched allow rule", allowRule);
        }
        return null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ValidationResult validateInput(String arguments, PermissionContext context) {
        try {
            BashInput input = parseInput(arguments);
            if (readOnly && !isReadOnlyCommand(input.command())) {
                return ValidationResult.invalid("Read-only Bash does not allow this command: " + input.command());
            }
            return ValidationResult.ok();
        } catch (IllegalArgumentException e) {
            return ValidationResult.invalid(e.getMessage());
        } catch (Exception e) {
            return ValidationResult.invalid("Failed to validate Bash input: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments, PermissionContext context) {
        try {
            BashInput input = parseInput(arguments);
            if (readOnly && !isReadOnlyCommand(input.command())) {
                return ToolResult.error("Read-only Bash does not allow this command: " + input.command());
            }

            ProcessBuilder pb = new ProcessBuilder("bash", "-c", input.command());
            Path workingDir = context == null ? null : context.workingDir();
            if (workingDir != null) {
                pb.directory(workingDir.toFile());
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (var reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ToolResult.error("bash command timed out");
            }
            return ToolResult.success(output.toString().trim());
        } catch (Exception e) {
            return ToolResult.error("bash command failed: " + e.getMessage());
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolResult execute(String arguments) {
        return execute(arguments, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ToolSpecification getSpec() {
        return ToolSpecification.builder()
                .name(name()).description(description())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("command", "Shell command to execute")
                        .required(List.of("command"))
                        .build())
                .build();
    }

    /**
     * 解析输入并返回输入。
     */
    private BashInput parseInput(String arguments) throws Exception {
        JsonNode args = objectMapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        if (!args.isObject()) {
            throw new IllegalArgumentException("arguments must be a JSON object");
        }
        String command = args.path("command").asText("");
        if (command.isBlank()) {
            throw new IllegalArgumentException("command is required");
        }
        return new BashInput(command.trim());
    }

    /**
     * 判断命令是否属于允许直接执行的只读命令集合。
     */
    private boolean isReadOnlyCommand(String command) {
        String normalized = command.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.isBlank()) {
            return false;
        }
        if (WRITE_OPERATOR_PATTERN.matcher(lower).find()
                || COMMAND_SEPARATOR_PATTERN.matcher(lower).find()
                || lower.contains("`")
                || lower.contains("$(")) {
            return false;
        }

        String first = firstToken(lower);
        return switch (first) {
            case "pwd", "ls", "cat", "head", "tail", "grep", "rg" -> true;
            case "find" -> !containsAnyToken(lower, "-delete", "-exec", "-execdir", "-ok", "-okdir");
            case "git" -> isReadOnlyGitCommand(lower);
            default -> false;
        };
    }

    /**
     * 判断 Git 子命令是否只读取仓库状态。
     */
    private boolean isReadOnlyGitCommand(String lowerCommand) {
        List<String> tokens = tokens(lowerCommand);
        if (tokens.size() < 2 || !"git".equals(tokens.get(0))) {
            return false;
        }
        String subcommand = tokens.get(1);
        if (containsOutputOption(tokens)) {
            return false;
        }
        return switch (subcommand) {
            case "status", "log", "diff", "show" -> true;
            default -> false;
        };
    }

    /**
     * 判断命令参数是否已包含调用方指定的输出格式选项。
     */
    private boolean containsOutputOption(List<String> tokens) {
        for (String token : tokens) {
            if ("--output".equals(token) || token.startsWith("--output=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析命令的首个可执行 token，供只读规则匹配。
     */
    private String firstToken(String command) {
        List<String> tokens = tokens(command);
        return tokens.isEmpty() ? "" : tokens.get(0);
    }

    /**
     * 判断命令文本是否包含任一危险控制 token。
     */
    private boolean containsAnyToken(String command, String... candidates) {
        List<String> tokens = tokens(command);
        for (String candidate : candidates) {
            if (tokens.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按连续空白拆分命令，返回用于权限判断的参数 token 列表。
     */
    private List<String> tokens(String command) {
        return Pattern.compile("\\s+")
                .splitAsStream(command.trim())
                .filter(token -> !token.isBlank())
                .toList();
    }

    /**
     * Bash 工具校验后的命令、超时和输出限制。
     */
    private record BashInput(String command) {}
}
