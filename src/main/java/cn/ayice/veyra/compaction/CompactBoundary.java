package cn.ayice.veyra.compaction;


import cn.ayice.veyra.context.TokenEstimator;
import cn.ayice.veyra.context.prompt.SystemPromptRegistry;
import cn.ayice.veyra.llm.AIService;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 压缩边界工具。全量压缩后会插入边界，ContextService 只发送边界之后的消息，避免旧历史被重复提交。
 */
public class CompactBoundary {

    public static final String BOUNDARY_PREFIX = "[CompactBoundary]";
    public static final String MICRO_BOUNDARY_PREFIX = "[MicroCompactBoundary]";

    /**
     * 判断消息是否为任意一种上下文压缩边界。
     */
    public static boolean isBoundary(ChatMessage msg) {
        return isFullBoundary(msg);
    }

    /**
     * 判断消息是否为替换早期历史的完整压缩边界。
     */
    public static boolean isFullBoundary(ChatMessage msg) {
        return msg instanceof SystemMessage sm
                && sm.text() != null
                && sm.text().startsWith(BOUNDARY_PREFIX);
    }

    /**
     * 判断消息是否为仅压缩工具结果的微压缩边界。
     */
    public static boolean isMicroBoundary(ChatMessage msg) {
        return msg instanceof SystemMessage sm
                && sm.text() != null
                && sm.text().startsWith(MICRO_BOUNDARY_PREFIX);
    }

    /**
     * 查找最近一个索引；不存在时返回空结果。
     */
    public static int findLastIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (isBoundary(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 返回最近压缩边界之后仍应发送给模型的消息。
     */
    public static List<ChatMessage> afterLastBoundary(List<ChatMessage> messages) {
        int idx = findLastIndex(messages);
        if (idx < 0) {
            return new ArrayList<>(messages);
        }
        return new ArrayList<>(messages.subList(idx + 1, messages.size()));
    }

    /**
     * CompactBoundary 的内容格式为：
     * [CompactBoundary] trigger={trigger} preTokens={preTokens} summarized={messagesSummarized}
     * 其中：
     * - trigger：触发压缩的原因，例如 "auto"、"manual"
     * - preTokens：压缩前的 token 数量
     * - summarized：被压缩的消息数量
     * @param trigger
     * @param preTokens
     * @param messagesSummarized
     * @return
     */
    public static ChatMessage create(String trigger, int preTokens, int messagesSummarized) {
        String content = BOUNDARY_PREFIX
                + " trigger=" + trigger
                + " preTokens=" + preTokens
                + " summarized=" + messagesSummarized;
        return SystemMessage.from(content);
    }

    /**
     * 根据输入创建对应对象。
     */
    public static ChatMessage createMicro(String trigger, int preTokens, int tokensSaved, List<String> compactedToolIds) {
        String content = MICRO_BOUNDARY_PREFIX
                + " trigger=" + trigger
                + " preTokens=" + preTokens
                + " tokensSaved=" + tokensSaved
                + " compactedToolIds=" + String.join(",", compactedToolIds == null ? List.of() : compactedToolIds);
        return SystemMessage.from(content);
    }

    /**
     * 返回移除完整压缩边界后的消息副本，保留普通消息和微压缩边界。
     */
    public static List<ChatMessage> withoutFullBoundaries(List<ChatMessage> messages) {
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (!isFullBoundary(message)) {
                result.add(message);
            }
        }
        return result;
    }
}
