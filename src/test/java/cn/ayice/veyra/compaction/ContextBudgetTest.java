package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.TokenEstimator;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetTest {

    @Test
    void measuresMessagesAndToolSchemaFromFinalRequest() {
        CompactionConfig config = new CompactionConfig(128_000, 4_096, true, true, null, true);
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("hello"))
                .toolSpecifications(ToolSpecification.builder()
                        .name("Read")
                        .description("read a file from disk")
                        .build())
                .build();

        assertTrue(CompactionService.measureRequest(request) > TokenEstimator.estimate(request.messages()));
    }

    @Test
    void classifiesUsingWarningAndCompactThresholds() {
        CompactionConfig config = new CompactionConfig(128_000, 4_096, true, true, null, true);
        assertEquals(CompactionService.CapacityState.NORMAL,
                CompactionService.classify(config.warningThreshold() - 1, config));
        assertEquals(CompactionService.CapacityState.WARNING,
                CompactionService.classify(config.warningThreshold(), config));
        assertEquals(CompactionService.CapacityState.COMPACT_REQUIRED,
                CompactionService.classify(config.threshold(), config));
    }
}
