package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.context.WorkingMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LoopStateTest {

    @Test
    void mutatesSingleProcessStateAndMarksLatestSequenceStable() {
        LoopState state = LoopState.initial(List.of(), 0, 0);

        LoopState returned = state
                .appendOriginal(UserMessage.from("message"))
                .markStable()
                .withTurnCount(2);

        assertSame(state, returned);
        assertEquals(1, state.nextSequence());
        assertEquals(1, state.stableSnapshot().endSequence());
        assertEquals(2, state.turnCount());
        assertEquals(List.of("message"), state.messages().stream()
                .map(WorkingMessage::message)
                .map(message -> ((UserMessage) message).singleText())
                .toList());
    }
}
