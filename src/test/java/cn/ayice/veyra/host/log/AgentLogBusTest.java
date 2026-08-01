package cn.ayice.veyra.host.log;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentLogBusTest {

    @Test
    void publishesRawLogLinesAndReplaysRecentLines() {
        AgentLogBus bus = new AgentLogBus(10);
        List<AgentLogLine> firstSubscriber = new ArrayList<>();

        AutoCloseable subscription = bus.subscribe(firstSubscriber::add, false);
        bus.publish("22:53:40.672 [pool-1-thread-4] INFO  AgentLoop - ==========第【1】轮==========\n");
        close(subscription);

        List<AgentLogLine> replayed = new ArrayList<>();
        bus.subscribe(replayed::add, true);

        assertEquals(1, firstSubscriber.size());
        assertEquals(1, replayed.size());
        assertEquals(firstSubscriber.get(0).line(), replayed.get(0).line());
        assertEquals("22:53:40.672 [pool-1-thread-4] INFO  AgentLoop - ==========第【1】轮==========\n",
                replayed.get(0).line());
    }

    private static void close(AutoCloseable subscription) {
        try {
            subscription.close();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
