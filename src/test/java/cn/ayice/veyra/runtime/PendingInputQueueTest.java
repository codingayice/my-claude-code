package cn.ayice.veyra.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingInputQueueTest {

    @Test
    void movesAgentFollowupToSteerExactlyOnce() {
        PendingInputQueue queue = new PendingInputQueue();
        PendingInputQueue.Message message = queue.addFollowup("调整方向", RunMode.AGENT);

        assertTrue(queue.steer(message.id()));
        assertFalse(queue.steer(message.id()));
        assertEquals(List.of(message), queue.drainSteers());
        assertNull(queue.takeForNextRun(message.id()));
    }

    @Test
    void lateSteerFallsBackToNextRunAndChatCannotSteer() {
        PendingInputQueue queue = new PendingInputQueue();
        PendingInputQueue.Message agent = queue.addFollowup("继续处理", RunMode.AGENT);
        PendingInputQueue.Message chat = queue.addFollowup("普通聊天", RunMode.CHAT);

        assertTrue(queue.steer(agent.id()));
        assertFalse(queue.steer(chat.id()));
        assertEquals(agent, queue.takeForNextRun(agent.id()));
        assertEquals(chat, queue.takeForNextRun(chat.id()));
    }
}
