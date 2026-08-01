package cn.ayice.veyra.conversation.context.compaction;

import cn.ayice.veyra.conversation.context.WorkingMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MicroCompactorTest {

    @Test
    void truncatesOnlyResultsBeforeRecentFiveAndKeepsHeadAndTail() {
        List<WorkingMessage> messages = historyWithToolResults(7, 2_500);

        CompactionResult result = new MicroCompactor().compact(messages, 1);

        ToolExecutionResultMessage first = (ToolExecutionResultMessage) result.messages().get(2).message();
        ToolExecutionResultMessage recent = (ToolExecutionResultMessage) result.messages().get(12).message();
        assertEquals(CompactStrategy.MICRO, result.strategy());
        assertTrue(first.text().startsWith("x".repeat(250)));
        assertTrue(first.text().contains("工具结果过长"));
        assertTrue(first.text().endsWith("x".repeat(250)));
        assertEquals("x".repeat(2_500), recent.text());
    }

    @Test
    void clearsOldResultsEveryFiftyModelRounds() {
        CompactionResult result = new MicroCompactor().compact(historyWithToolResults(6, 2_500), 50);

        ToolExecutionResultMessage first = (ToolExecutionResultMessage) result.messages().get(2).message();
        assertEquals(MicroCompactor.CLEARED_RESULT, first.text());
    }

    private static List<WorkingMessage> historyWithToolResults(int count, int resultLength) {
        List<WorkingMessage> messages = new ArrayList<>();
        long sequence = 1;
        messages.add(WorkingMessage.original(sequence++, UserMessage.from("start")));
        for (int index = 0; index < count; index++) {
            ToolExecutionRequest request = ToolExecutionRequest.builder()
                    .id("tool-" + index)
                    .name("Read")
                    .arguments("{}")
                    .build();
            messages.add(WorkingMessage.original(sequence++, AiMessage.from(List.of(request))));
            messages.add(WorkingMessage.original(sequence++,
                    ToolExecutionResultMessage.from(request, "x".repeat(resultLength))));
        }
        return messages;
    }
}
