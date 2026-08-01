package cn.ayice.veyra.conversation.context;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinalRequestValidatorTest {

    @Test
    void acceptsCompleteOrderedToolBatch() {
        ToolExecutionRequest first = request("tool-1", "Read");
        ToolExecutionRequest second = request("tool-2", "Grep");
        ChatRequest request = ChatRequest.builder().messages(List.of(
                UserMessage.from("inspect"),
                AiMessage.from(List.of(first, second)),
                ToolExecutionResultMessage.from(first, "a"),
                ToolExecutionResultMessage.from(second, "b")
        )).build();

        assertTrue(new FinalRequestValidator().validate(request).valid());
    }

    @Test
    void rejectsMissingToolResultWithoutMutatingRequest() {
        ToolExecutionRequest toolRequest = request("tool-1", "Read");
        ChatRequest request = ChatRequest.builder().messages(List.of(
                UserMessage.from("inspect"),
                AiMessage.from(List.of(toolRequest))
        )).build();

        FinalRequestValidator.ValidationResult result = new FinalRequestValidator().validate(request);

        assertFalse(result.valid());
        assertEquals("MISSING_TOOL_RESULT", result.errorCode());
        assertEquals(2, request.messages().size());
    }

    private static ToolExecutionRequest request(String id, String name) {
        return ToolExecutionRequest.builder()
                .id(id)
                .name(name)
                .arguments("{}")
                .build();
    }
}
