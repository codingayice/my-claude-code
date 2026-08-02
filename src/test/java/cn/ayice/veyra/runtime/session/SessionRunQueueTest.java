package cn.ayice.veyra.runtime.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRunQueueTest {

    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    @AfterEach
    void shutdownExecutor() {
        executor.shutdownNow();
    }

    @Test
    void serializesRunsSubmittedToTheSameSession() throws Exception {
        SessionRunQueue queue = new SessionRunQueue(executor);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();

        var first = queue.submit(() -> {
            order.add("first-start");
            firstStarted.countDown();
            await(releaseFirst);
            order.add("first-end");
        });
        var second = queue.submit(() -> {
            order.add("second-start");
            secondStarted.countDown();
        });

        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));
        releaseFirst.countDown();

        first.get(1, TimeUnit.SECONDS);
        second.get(1, TimeUnit.SECONDS);
        assertEquals(List.of("first-start", "first-end", "second-start"), order);
    }

    @Test
    void differentSessionQueuesCanRunConcurrently() throws Exception {
        SessionRunQueue firstQueue = new SessionRunQueue(executor);
        SessionRunQueue secondQueue = new SessionRunQueue(executor);
        CountDownLatch bothStarted = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        var first = firstQueue.submit(() -> {
            bothStarted.countDown();
            await(release);
        });
        var second = secondQueue.submit(() -> {
            bothStarted.countDown();
            await(release);
        });

        assertTrue(bothStarted.await(1, TimeUnit.SECONDS));
        release.countDown();
        first.get(1, TimeUnit.SECONDS);
        second.get(1, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test interrupted", e);
        }
    }
}
