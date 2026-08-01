package cn.ayice.veyra.llm;

import cn.ayice.veyra.config.AppConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * LLM 客户端封装。它负责普通聊天、工具调用轮次和摘要生成等模型请求。
 */
public class AIService implements ChatStreamer {

    private final ChatModel model;

    private final StreamingChatModel streamingModel;

    /**
     * 使用同一份模型配置创建同步和流式 LangChain4j 客户端。
     */
    public AIService(AppConfig config) {
        this.model = OpenAiChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .timeout(Duration.ofSeconds(config.getModelTimeoutSeconds()))
                .build();

        this.streamingModel = OpenAiStreamingChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(config.getTemperature())
                .maxTokens(config.getMaxTokens())
                .returnThinking(true)
                .timeout(Duration.ofSeconds(config.getModelTimeoutSeconds()))
                .build();
    }

    /**
     * 同步调用模型，主要用于上下文摘要等不需要增量事件的场景。
     */
    public ChatResponse chat(ChatRequest request) {
        return model.chat(request);
    }

    /**
     * 发起支持工具调用的流式模型请求，并将正文 token 转发给调用方。
     */
    public CompletableFuture<AiMessage> streamingChat(
            List<ChatMessage> messages,
            List<ToolSpecification> toolSpecs,
            Consumer<String> onToken
    ) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .toolSpecifications(toolSpecs)
                .build();
        return streamingChat(request, ignored -> {}, onToken);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public CompletableFuture<AiMessage> streamingChatOnly(
            List<ChatMessage> messages,
            Consumer<String> onThinking,
            Consumer<String> onToken
    ) {
        ChatRequest request = ChatRequest.builder()
                .messages(messages)
                .build();
        return streamingChat(request, onThinking, onToken);
    }

    /**
     * 将 LangChain4j 回调式流响应转换成可等待完整 AiMessage 的 Future。
     */
    private CompletableFuture<AiMessage> streamingChat(
            ChatRequest request,
            Consumer<String> onThinking,
            Consumer<String> onToken
    ) {
        CompletableFuture<AiMessage> future = new CompletableFuture<>();

        // token/thinking 实时向外转发，只有 complete/error 回调会终结 Future。
        streamingModel.chat(request, new StreamingChatResponseHandler() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void onPartialResponse(String token) {
                if(token != null && !token.isEmpty()) {
                    onToken.accept(token);
                }
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onPartialThinking(dev.langchain4j.model.chat.response.PartialThinking partialThinking) {
                String thinking = partialThinking == null ? null : partialThinking.text();
                if (thinking != null && !thinking.isEmpty()) {
                    onThinking.accept(thinking);
                }
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onCompleteResponse(ChatResponse response) {
                future.complete(response.aiMessage());
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onError(Throwable error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }
}
