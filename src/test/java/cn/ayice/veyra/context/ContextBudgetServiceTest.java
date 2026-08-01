package cn.ayice.veyra.context;

import cn.ayice.veyra.compaction.AutoCompactConfig;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextBudgetServiceTest {

    @Test
    void measuresMessagesAndToolSchemaFromFinalRequest() {
        AutoCompactConfig config = new AutoCompactConfig(128_000, 4_096, true, true, null, true);
        ContextBudgetService service = new ContextBudgetService(config);
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from("hello"))
                .toolSpecifications(ToolSpecification.builder()
                        .name("Read")
                        .description("read a file from disk")
                        .build())
                .build();

        assertTrue(service.measure(request) > TokenEstimator.estimate(request.messages()));
    }

    @Test
    void classifiesUsingWarningAndCompactThresholds() {
        AutoCompactConfig config = new AutoCompactConfig(128_000, 4_096, true, true, null, true);
        ContextBudgetService service = new ContextBudgetService(config);

        assertEquals(ContextBudgetService.CapacityState.NORMAL,
                service.classify(config.warningThreshold() - 1));
        assertEquals(ContextBudgetService.CapacityState.WARNING,
                service.classify(config.warningThreshold()));
        assertEquals(ContextBudgetService.CapacityState.COMPACT_REQUIRED,
                service.classify(config.threshold()));
    }
}
