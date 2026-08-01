package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.WorkingMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class ConversationChunkerTest {

    @Test
    void splitsOnlyBeforeRealUserTurns() {
        List<WorkingMessage> messages = List.of(
                WorkingMessage.original(1, UserMessage.from("first " + "x".repeat(100))),
                WorkingMessage.original(2, AiMessage.from("answer one")),
                WorkingMessage.original(3, UserMessage.from("second " + "y".repeat(100))),
                WorkingMessage.original(4, AiMessage.from("answer two"))
        );

        List<List<WorkingMessage>> chunks = new ConversationChunker().split(messages, 40);

        assertEquals(2, chunks.size());
        assertInstanceOf(UserMessage.class, chunks.get(0).get(0).message());
        assertInstanceOf(UserMessage.class, chunks.get(1).get(0).message());
    }
}
