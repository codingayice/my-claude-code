package cn.ayice.veyra.context;


import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.prompt.SystemPromptBuilder;
import cn.ayice.veyra.memory.MemoryService.Context;
import cn.ayice.veyra.memory.MemoryService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 发送给 LLM 的 ChatRequest 构建器。它先生成系统提示词，再追加压缩边界后的历史消息，并附上当前工具 schema。
 */
public class ContextService {

    private final SystemPromptBuilder promptBuilder;
    private final List<ToolSpecification> toolSpecifications;
    private final Map<String, String> toolDescriptions;
    private final MemoryService memoryService;

    /**
     * 使用固定工具元数据、运行配置、记忆和压缩配置创建上下文构建器。
     */
    public ContextService(
            List<ToolSpecification> toolSpecifications,
            Map<String, String> toolDescriptions,
            AppConfig config,
            MemoryService memoryService,
            TokenBudget tokenBudget
    ) {
        this.toolSpecifications = List.copyOf(toolSpecifications);
        this.toolDescriptions = Map.copyOf(toolDescriptions);
        this.memoryService = memoryService;
        this.promptBuilder = new SystemPromptBuilder(toolSpecifications, toolDescriptions, config, tokenBudget);
    }

    /**
     * 清除系统提示词稳定片段缓存。
     */
    public void clearPromptCache() {
        promptBuilder.clearCache();
    }

    /**
     * 使用带原始序号的工作历史构造请求；合成摘要和恢复消息不会被误认为本轮真实用户输入。
     */
    public ChatRequest buildWorking(List<WorkingMessage> history, Path workingDir) {
        List<ChatMessage> messages = buildSystemMessages(workingDir);
        int currentUserIndex = latestOriginalUserMessageIndex(history);
        MemoryService.Context memoryContext = buildMemoryContext(history, currentUserIndex);
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
        return promptBuilder.build(workingDir);
    }

    /**
     * 只使用已定位的真实用户输入召回本轮长期记忆参考。
     */
    private MemoryService.Context buildMemoryContext(List<WorkingMessage> messages, int userIndex) {
        if (memoryService == null || userIndex >= messages.size()
                || !(messages.get(userIndex).message() instanceof UserMessage userMessage)) {
            return MemoryService.Context.empty();
        }
        try {
            return memoryService.buildContext(userMessage.singleText());
        } catch (Exception unsupportedUserMessage) {
            return MemoryService.Context.empty();
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

    /**
     * Context 构建所需的只读窗口信息，使 Context 不反向依赖 Compaction 配置类型。
     */
    public record TokenBudget(int maxContextTokens, int effectiveWindow, int compactThreshold) {
    }

}
