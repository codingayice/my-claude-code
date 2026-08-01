package cn.ayice.veyra.host.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SLF4J 控制台日志的内存总线。Logback appender 写入这里，HTTP SSE 从这里订阅。
 */
public class AgentLogBus {

    private static final int DEFAULT_REPLAY_LIMIT = 500;
    private static final AgentLogBus GLOBAL = new AgentLogBus(DEFAULT_REPLAY_LIMIT);

    private final AtomicLong seq = new AtomicLong();
    private final int replayLimit;
    private final ArrayDeque<AgentLogLine> recentLines = new ArrayDeque<>();
    private final CopyOnWriteArrayList<Consumer<AgentLogLine>> subscribers = new CopyOnWriteArrayList<>();

    public AgentLogBus(int replayLimit) {
        if (replayLimit <= 0) {
            throw new IllegalArgumentException("replayLimit must be positive");
        }
        this.replayLimit = replayLimit;
    }

    /**
     * 返回进程级日志总线单例。
     */
    public static AgentLogBus global() {
        return GLOBAL;
    }

    /**
     * 将事件发布给当前全部有效订阅者。
     */
    public void publish(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        AgentLogLine event = new AgentLogLine(seq.incrementAndGet(), System.currentTimeMillis(), line);
        synchronized (recentLines) {
            recentLines.addLast(event);
            while (recentLines.size() > replayLimit) {
                recentLines.removeFirst();
            }
        }
        for (Consumer<AgentLogLine> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }

    /**
     * 注册订阅者并返回用于解除订阅的关闭句柄。
     */
    public AutoCloseable subscribe(Consumer<AgentLogLine> subscriber, boolean replayRecent) {
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber must not be null");
        }
        subscribers.add(subscriber);
        if (replayRecent) {
            for (AgentLogLine line : recentSnapshot()) {
                subscriber.accept(line);
            }
        }
        return () -> subscribers.remove(subscriber);
    }

    /**
     * 返回最近日志缓冲区的线程安全快照。
     */
    private List<AgentLogLine> recentSnapshot() {
        synchronized (recentLines) {
            return new ArrayList<>(recentLines);
        }
    }
}
