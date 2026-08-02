package cn.ayice.veyra.context.prompt;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.context.instruction.ProjectInstructionLoader;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * 按稳定顺序组装系统提示词，并缓存不随请求变化的片段。
 */
public final class SystemPromptBuilder {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptBuilder.class);

    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, String> toolDescriptions;
    private final AppConfig config;
    private final ContextService.TokenBudget tokenBudget;
    private final Map<String, String> cache = new LinkedHashMap<>();

    /**
     * 使用现有工具、应用配置和上下文预算创建提示词构建器。
     */
    public SystemPromptBuilder(
            List<ToolSpecification> toolSpecifications,
            Map<String, String> toolDescriptions,
            AppConfig config,
            ContextService.TokenBudget tokenBudget
    ) {
        this.toolSpecifications = List.copyOf(toolSpecifications);
        this.toolDescriptions = Map.copyOf(toolDescriptions);
        this.config = config;
        this.tokenBudget = tokenBudget;
    }

    /**
     * 按原有顺序构造系统消息；项目指令每次读取，其余片段沿用会话内缓存。
     */
    public List<ChatMessage> build(Path workingDir) {
        List<ChatMessage> messages = new ArrayList<>();
        addCached(messages, "intro", PromptTemplates::intro);
        addCached(messages, "self_description", PromptTemplates::selfDescription);
        addCached(messages, "todo_planning", PromptTemplates::todoPlanning);
        addCached(messages, "subagent", PromptTemplates::subagent);
        addCached(messages, "actions", PromptTemplates::actions);
        addCached(messages, "communication_style", PromptTemplates::communicationStyle);
        addCached(messages, "tools", this::tools);
        addCached(messages, "environment_info", () -> environment(workingDir));
        addDynamic(messages, "project-instructions", () -> projectInstructions(workingDir));
        addCached(messages, "memory-policy", PromptTemplates::memoryPolicy);
        addCached(messages, "token_budget", this::tokenBudget);
        return messages;
    }

    /**
     * 清除稳定片段缓存，使下一次构建重新计算全部内容。
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * 添加可缓存片段，并保持单个片段失败不影响其他系统消息。
     */
    private void addCached(List<ChatMessage> messages, String name, Supplier<String> supplier) {
        try {
            String content = cache.computeIfAbsent(name, ignored -> supplier.get());
            add(messages, content);
        } catch (Exception error) {
            log.error("构建系统提示词 section '{}' 失败", name, error);
        }
    }

    /**
     * 添加每次请求重新计算的动态片段。
     */
    private void addDynamic(List<ChatMessage> messages, String name, Supplier<String> supplier) {
        try {
            add(messages, supplier.get());
        } catch (Exception error) {
            log.error("构建系统提示词 section '{}' 失败", name, error);
        }
    }

    /**
     * 将非空文本转换成系统消息。
     */
    private static void add(List<ChatMessage> messages, String content) {
        if (content != null && !content.isBlank()) {
            messages.add(SystemMessage.from(content));
        }
    }

    /**
     * 构造模型可见工具清单和稳定使用规则。
     */
    private String tools() {
        String tools = toolSpecifications.stream()
                .map(spec -> {
                    String description = toolDescriptions.get(spec.name());
                    return description == null
                            ? "- %s".formatted(spec.name())
                            : "- %s: %s".formatted(spec.name(), description);
                })
                .collect(Collectors.joining("\n"));
        String availableTools = tools.isEmpty()
                ? "可用工具:\n\n"
                : "可用工具:\n\n%s\n".formatted(tools);
        return """
                %s
                工具使用指南:
                - 优先使用 Read / Glob / Grep 了解代码，再使用 Edit / Write 修改
                - 相互独立的工具调用尽量在同一轮中并行发起
                - 小改动优先使用 Edit 而非用 Write 重写整个文件
                - Bash 可执行编译、运行、git 只读检查等命令
                - Agent 用于处理独立的探索、计划、验证或局部实现任务\
                """.formatted(availableTools);
    }

    /**
     * 构造工作目录、平台和模型信息。
     */
    private String environment(Path workingDir) {
        return """
                工作目录 workingDir: %s
                路径规则:
                - 工具中的相对路径只基于 workingDir 解析
                - 如果用户要求操作 workingDir 之外的目录，必须使用绝对路径
                - 是否允许访问由权限系统判断，不要自行假设
                平台: %s
                模型: %s\
                """.formatted(
                workingDir,
                System.getProperty("os.name", "unknown"),
                config.getModelName()
        );
    }

    /**
     * 加载当前工作区的项目指令；缺失或失败时不产生系统消息。
     */
    private String projectInstructions(Path workingDir) {
        try {
            String instructions = ProjectInstructionLoader.defaults(workingDir).load();
            return instructions == null || instructions.isBlank()
                    ? null
                    : "# Project instructions\n\n" + instructions.trim();
        } catch (Exception error) {
            log.error("加载项目指令失败, workspace={}", workingDir, error);
            return null;
        }
    }

    /**
     * 构造模型可见的上下文窗口和压缩阈值说明。
     */
    private String tokenBudget() {
        return "上下文预算:\n"
                + "- 模型上下文窗口: " + tokenBudget.maxContextTokens() + " tokens\n"
                + "- 有效窗口: " + tokenBudget.effectiveWindow() + " tokens\n"
                + "- 压缩触发阈值: " + tokenBudget.compactThreshold() + " tokens\n"
                + "- 超出阈值时自动压缩旧历史，保留最近消息\n"
                + "- 请根据剩余空间控制输出长度";
    }
}
