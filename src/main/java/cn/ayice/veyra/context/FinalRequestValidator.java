package cn.ayice.veyra.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 最终模型请求的只读结构验证器。它只报告工具调用配对错误，不修复、删除或补造消息。
 */
public final class FinalRequestValidator {

    /**
     * 验证工具调用 ID 唯一、请求结果完整且结果顺序与请求顺序一致。
     */
    public ValidationResult validate(ChatRequest request) {
        Set<String> requestIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        List<String> requestOrder = new ArrayList<>();
        List<String> resultOrder = new ArrayList<>();

        for (ChatMessage message : request.messages()) {
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    if (!requestIds.add(toolRequest.id())) {
                        return ValidationResult.invalid("DUPLICATE_TOOL_USE", toolRequest.id());
                    }
                    requestOrder.add(toolRequest.id());
                }
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                if (!requestIds.contains(toolResult.id())) {
                    return ValidationResult.invalid("ORPHAN_TOOL_RESULT", toolResult.id());
                }
                if (!resultIds.add(toolResult.id())) {
                    return ValidationResult.invalid("DUPLICATE_TOOL_RESULT", toolResult.id());
                }
                resultOrder.add(toolResult.id());
            }
        }

        for (String requestId : requestOrder) {
            if (!resultIds.contains(requestId)) {
                return ValidationResult.invalid("MISSING_TOOL_RESULT", requestId);
            }
        }
        if (!requestOrder.equals(resultOrder)) {
            return ValidationResult.invalid("TOOL_RESULT_ORDER", "");
        }
        return ValidationResult.success();
    }

    /**
     * 最终请求结构验证结果。
     */
    public record ValidationResult(boolean valid, String errorCode, String toolCallId) {
        /**
         * 创建通过全部工具配对和顺序检查的结果。
         */
        public static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }

        /**
         * 创建携带稳定错误码和相关工具调用 ID 的失败结果。
         */
        public static ValidationResult invalid(String errorCode, String toolCallId) {
            return new ValidationResult(false, errorCode, toolCallId);
        }
    }
}
