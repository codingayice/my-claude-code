package cn.ayice.veyra.llm;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 模型流式输出辅助器。它把增量响应适配给需要边生成边消费文本的调用方。
 */
public interface ChatStreamer {
    /**
     * 发起不携带工具规范的流式模型调用。
     */
    CompletableFuture<AiMessage> streamingChatOnly(
            List<ChatMessage> messages,
            Consumer<String> onThinking,
            Consumer<String> onToken
    );
}
