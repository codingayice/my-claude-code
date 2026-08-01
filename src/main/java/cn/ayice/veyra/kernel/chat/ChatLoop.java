package cn.ayice.veyra.kernel.chat;

import cn.ayice.veyra.llm.ChatStreamer;

import cn.ayice.veyra.conversation.transcript.TranscriptRecorder;
import cn.ayice.veyra.kernel.event.AgentEventSink;
import cn.ayice.veyra.kernel.model.ModelCallExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 命令行模式的聊天循环。它把终端输入交给 AgentLoop，并把返回结果输出到终端。
 */
public class ChatLoop {
    private static final Logger log = LoggerFactory.getLogger(ChatLoop.class);
    private final ChatStreamer chat;
    private final AgentEventSink eventSink;
    private final long modelCallTimeoutMs;
    private final TranscriptRecorder transcriptRecorder;
    private List<ChatMessage> history = new ArrayList<>();

    public ChatLoop(
            ChatStreamer chat,
            AgentEventSink eventSink,
            long modelCallTimeoutMs,
            List<ChatMessage> initialHistory,
            TranscriptRecorder transcriptRecorder
    ) {
        this.chat = chat;
        this.eventSink = eventSink;
        this.modelCallTimeoutMs = modelCallTimeoutMs;
        this.history = new ArrayList<>(initialHistory);
        this.transcriptRecorder = transcriptRecorder;
    }

    /**
     * 处理一条用户输入并返回本次循环最终文本。
     */
    public String process(String input) {
        eventSink.emit("user.message", eventPayload("text", input));
        UserMessage userMessage = UserMessage.from(input);
        transcriptRecorder.record(userMessage);
        List<ChatMessage> messages = appendMessage(history, userMessage);

        AiMessage aiMessage;
        try {
            CompletableFuture<AiMessage> future = chat.streamingChatOnly(
                    messages,
                    thinking -> {
            if (thinking != null && !thinking.isEmpty()) {
                eventSink.emit("assistant.thinking.token", eventPayload("text", thinking));
            }
        },
        token -> {
            if (token != null && !token.isEmpty()) {
                eventSink.emit("assistant.token", eventPayload("text", token));
            }
        }
            );
            aiMessage = ModelCallExecutor.await(future, modelCallTimeoutMs);
        } catch (Exception e) {
            log.error("Chat 模型调用失败", e);
            String errorMessage = ModelCallExecutor.safeErrorMessage(e);
            String error = "<error>LLM 调用失败: " + errorMessage + "。请重试。</error>";
            eventSink.emit("run.failed", eventPayload("content", error, "error", errorMessage));
            return error;
        }

        history = appendMessage(messages, aiMessage);
        transcriptRecorder.record(aiMessage);
        String text = aiMessage.text() == null ? "" : aiMessage.text();
        String thinking = aiMessage.thinking() == null ? "" : aiMessage.thinking();
        eventSink.emit("assistant.message.completed", eventPayload(
                "text", text,
                "thinking", thinking,
                "hasToolRequests", false,
                "outputFormat", "text"
        ));
        eventSink.emit("run.completed", eventPayload("reason", "completed", "content", text));
        return text;
    }

    /**
     * 返回当前对话历史的防御性副本，调用方修改不会影响循环内部状态。
     */
    public List<ChatMessage> getHistory() {
        return new ArrayList<>(history);
    }

    /**
     * 将消息追加到目标内容。
     */
    private static List<ChatMessage> appendMessage(List<ChatMessage> messages, ChatMessage message) {
        List<ChatMessage> next = new ArrayList<>(messages);
        next.add(message);
        return next;
    }

    /**
     * 构建任务事件使用的稳定字段集合。
     */
    private static Map<String, Object> eventPayload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            payload.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return payload;
    }
}
