package cn.ayice.veyra.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PendingInputQueueTest {

    @Test
    void cancelsFollowupAndSteeringMessagesBeforeTheyAreConsumed() {
        PendingInputQueue queue = new PendingInputQueue();
        PendingInputQueue.Message followup = queue.addFollowup("稍后执行", RunMode.AGENT);
        PendingInputQueue.Message steering = queue.addFollowup("调整方向", RunMode.AGENT);
        assertTrue(queue.steer(steering.id()));

        assertTrue(queue.cancel(followup.id()));
        assertTrue(queue.cancel(steering.id()));
        assertFalse(queue.cancel(steering.id()));
        assertNull(queue.takeForNextRun(followup.id()));
        assertTrue(queue.drainSteers().isEmpty());
    }

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
