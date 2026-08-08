package cn.ayice.veyra.session.persistence;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Journal payload 与 LangChain4j 消息之间的稳定编解码边界。
 */
public final class JournalMessageCodec {

    private JournalMessageCodec() {
    }

    /** 返回给定模型消息对应的稳定 Journal 类型。 */
    public static String typeOf(ChatMessage message) {
        if (message instanceof UserMessage) {
            return SessionJournalTypes.USER_MESSAGE_RECORDED;
        }
        if (message instanceof AiMessage) {
            return SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED;
        }
        if (message instanceof ToolExecutionResultMessage) {
            return SessionJournalTypes.TOOL_RESULT_RECORDED;
        }
        throw new IllegalArgumentException("不支持持久化的消息类型: " + message.type());
    }

    /** 将支持的模型消息编码为不依赖 LangChain4j 内部格式的 payload。 */
    public static Map<String, Object> encode(ChatMessage message) {
        if (message instanceof UserMessage user) {
            String text = userText(user);
            boolean visible = !(text.startsWith("<task_notifications>")
                    || text.startsWith("<system-reminder>"));
            return Map.of("text", text, "visible", visible);
        }
        if (message instanceof AiMessage ai) {
            List<Map<String, Object>> calls = ai.toolExecutionRequests().stream()
                    .map(request -> Map.<String, Object>of(
                            "id", request.id(),
                            "name", request.name(),
                            "arguments", request.arguments() == null ? "{}" : request.arguments()
                    ))
                    .toList();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("text", ai.text() == null ? "" : ai.text());
            payload.put("thinking", ai.thinking() == null ? "" : ai.thinking());
            payload.put("toolCalls", calls);
            return payload;
        }
        if (message instanceof ToolExecutionResultMessage result) {
            String outcome = outcome(result.text());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("toolUseId", result.id());
            payload.put("name", result.toolName());
            payload.put("success", "COMPLETED".equals(outcome));
            payload.put("outcome", outcome);
            payload.put("content", result.text() == null ? "" : result.text());
            return payload;
        }
        throw new IllegalArgumentException("不支持持久化的消息类型: " + message.type());
    }

    /** 将消息事实恢复为 LangChain4j 消息；非消息事实返回空值。 */
    public static ChatMessage decode(SessionJournalEntry entry) {
        Map<String, Object> payload = entry.payload();
        return switch (entry.type()) {
            case SessionJournalTypes.USER_MESSAGE_RECORDED ->
                    UserMessage.from(text(payload, "text"));
            case SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED -> decodeAssistant(payload);
            case SessionJournalTypes.TOOL_RESULT_RECORDED -> ToolExecutionResultMessage.from(
                    text(payload, "toolUseId"),
                    text(payload, "name"),
                    text(payload, "content")
            );
            default -> null;
        };
    }

    /** 恢复包含完整 ToolUse 的 Assistant 消息。 */
    private static AiMessage decodeAssistant(Map<String, Object> payload) {
        List<ToolExecutionRequest> requests = new ArrayList<>();
        Object rawCalls = payload.get("toolCalls");
        if (rawCalls instanceof List<?> calls) {
            for (Object rawCall : calls) {
                if (!(rawCall instanceof Map<?, ?> call)) {
                    continue;
                }
                requests.add(ToolExecutionRequest.builder()
                        .id(value(call, "id", ""))
                        .name(value(call, "name", ""))
                        .arguments(value(call, "arguments", "{}"))
                        .build());
            }
        }
        return AiMessage.builder()
                .text(text(payload, "text"))
                .thinking(text(payload, "thinking"))
                .toolExecutionRequests(requests)
                .build();
    }

    /** 提取用户消息的稳定文本表示。 */
    private static String userText(UserMessage message) {
        return message.hasSingleText() ? message.singleText() : message.toString();
    }

    /** 从字符串键 payload 中读取安全文本。 */
    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 从任意键值 Map 中读取带默认值的文本。 */
    private static String value(Map<?, ?> payload, String key, String fallback) {
        Object value = payload.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    /** 根据稳定包装标记判断工具结果的持久化终态。 */
    private static String outcome(String content) {
        if (content != null && content.contains("<rejected>")) {
            return "REJECTED";
        }
        if (content != null && (content.contains("<error>") || content.contains("<tool-interrupted"))) {
            return "FAILED";
        }
        return "COMPLETED";
    }
}
