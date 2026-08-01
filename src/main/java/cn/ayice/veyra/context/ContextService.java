package cn.ayice.veyra.context;


import cn.ayice.veyra.compaction.AutoCompactConfig;
import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.prompt.ActionsSection;
import cn.ayice.veyra.context.prompt.CommunicationStyleSection;
import cn.ayice.veyra.context.prompt.EnvironmentInfoSection;
import cn.ayice.veyra.context.prompt.IntroSection;
import cn.ayice.veyra.context.prompt.MemoryPolicySection;
import cn.ayice.veyra.context.prompt.ProjectInstructionSection;
import cn.ayice.veyra.context.prompt.SelfDescriptionSection;
import cn.ayice.veyra.context.prompt.SubagentSection;
import cn.ayice.veyra.context.prompt.SystemPromptContext;
import cn.ayice.veyra.context.prompt.SystemPromptRegistry;
import cn.ayice.veyra.context.prompt.TodoPlanningSection;
import cn.ayice.veyra.context.prompt.TokenBudgetSection;
import cn.ayice.veyra.context.prompt.ToolsSection;
import cn.ayice.veyra.memory.MemoryContext;
import cn.ayice.veyra.memory.MemoryContextBuilder;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 发送给 LLM 的 ChatRequest 构建器。它先生成系统提示词，再追加压缩边界后的历史消息，并附上当前工具 schema。
 */
public class ContextService {

    private final SystemPromptRegistry promptRegistry;
    private final AppConfig config;
    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, String> toolDescriptions;
    private final MemoryContextBuilder memoryContextService;

    /**
     * 使用固定工具元数据、运行配置、记忆和压缩配置创建上下文构建器。
     */
    public ContextService(
            List<ToolSpecification> toolSpecifications,
            Map<String, String> toolDescriptions,
            AppConfig config,
            MemoryContextBuilder memoryContextService,
            AutoCompactConfig compactConfig
    ) {
        this.config = config;
        this.toolSpecifications = List.copyOf(toolSpecifications);
        this.toolDescriptions = Map.copyOf(toolDescriptions);
        this.memoryContextService = memoryContextService;
        this.promptRegistry = buildRegistry(Objects.requireNonNull(compactConfig, "compactConfig"));
    }

    /**
     * 按稳定顺序注册系统提示词片段；顺序变化会直接改变模型输入。
     */
    private SystemPromptRegistry buildRegistry(AutoCompactConfig compactConfig) {
        SystemPromptRegistry registry = new SystemPromptRegistry();
        registry.register(new IntroSection());
        registry.register(new SelfDescriptionSection());
        registry.register(new TodoPlanningSection());
        registry.register(new SubagentSection());
        registry.register(new ActionsSection());
        registry.register(new CommunicationStyleSection());
        registry.register(new ToolsSection());
        registry.register(new EnvironmentInfoSection());
        registry.register(new ProjectInstructionSection());
        registry.register(new MemoryPolicySection());
        registry.register(new TokenBudgetSection(compactConfig));
        return registry;
    }

    /**
     * 返回系统提示词注册表，供压缩后清理缓存。
     */
    public SystemPromptRegistry getPromptRegistry() {
        return promptRegistry;
    }

    /**
     * 使用带原始序号的工作历史构造请求；合成摘要和恢复消息不会被误认为本轮真实用户输入。
     */
    public ChatRequest buildWorking(List<WorkingMessage> history, Path workingDir) {
        List<ChatMessage> messages = buildSystemMessages(workingDir);
        int currentUserIndex = latestOriginalUserMessageIndex(history);
        MemoryContext memoryContext = buildMemoryContext(history, currentUserIndex);
        List<ChatMessage> unwrapped = WorkingMessage.unwrap(history);
        if (memoryContext.message() == null) {
            messages.addAll(unwrapped);
        } else {
            messages.addAll(unwrapped.subList(0, currentUserIndex));
            messages.add(memoryContext.message());
            messages.addAll(unwrapped.subList(currentUserIndex, unwrapped.size()));
        }
        return ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecifications)
                .build();
    }

    /**
     * 按注册顺序为当前工作目录重新生成系统提示词消息。
     */
    private List<ChatMessage> buildSystemMessages(Path workingDir) {
        return promptRegistry.build(new SystemPromptContext(
                config,
                toolSpecifications,
                toolDescriptions,
                workingDir
        ));
    }

    /**
     * 只使用已定位的真实用户输入召回本轮长期记忆参考。
     */
    private MemoryContext buildMemoryContext(List<WorkingMessage> messages, int userIndex) {
        if (memoryContextService == null || userIndex >= messages.size()
                || !(messages.get(userIndex).message() instanceof UserMessage userMessage)) {
            return MemoryContext.empty();
        }
        try {
            return memoryContextService.build(userMessage.singleText());
        } catch (Exception unsupportedUserMessage) {
            return MemoryContext.empty();
        }
    }

    /**
     * 从后向前定位带 sequence 的用户消息，跳过摘要和恢复等合成 UserMessage。
     */
    private static int latestOriginalUserMessageIndex(List<WorkingMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            WorkingMessage message = messages.get(index);
            if (message.sequence().isPresent() && message.message() instanceof UserMessage) {
                return index;
            }
        }
        return messages.size();
    }

}
