package cn.ayice.veyra.session.event;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Ordered event stream owned by one active session runtime.
 */
public class SessionEventStream {

    private final AtomicLong seq = new AtomicLong(0);
    private final List<AgentEventSubscriber> subscribers = new CopyOnWriteArrayList<>();
    private final String sessionId;
    private final LongSupplier revisionSupplier;
    private volatile String runId;

    public SessionEventStream(String sessionId) {
        this(sessionId, 0L, () -> 0L);
    }

    /** 使用持久化全局 revision 创建流，避免 Runtime 重建后 SSE 序号回退。 */
    public SessionEventStream(String sessionId, long initialRevision) {
        this(sessionId, initialRevision, () -> initialRevision);
    }

    /** 使用独立的 SSE 序号和权威 Journal revision 创建事件流。 */
    public SessionEventStream(String sessionId, long initialSequence, LongSupplier revisionSupplier) {
        this.sessionId = sessionId;
        this.seq.set(Math.max(0L, initialSequence));
        this.revisionSupplier = revisionSupplier;
    }

    /**
     * 将后续会话事件关联到当前 runId。
     */
    public void bindRun(String runId) {
        this.runId = runId;
    }

    /**
     * 将给定项加入订阅者。
     */
    public void addSubscriber(AgentEventSubscriber subscriber) {
        subscribers.add(subscriber);
    }

    /**
     * 注销事件订阅者，后续发布不再向其发送事件。
     */
    public void removeSubscriber(AgentEventSubscriber subscriber) {
        subscribers.remove(subscriber);
        subscriber.close();
    }

    /**
     * 原子递增并返回当前会话的下一事件序号。
     */
    public long nextSeq() {
        return seq.incrementAndGet();
    }

    /**
     * 处理并传播 {@code emit} 对应的事件。
     */
    public void emit(String type, Map<String, Object> payload) {
        AgentEvent event = AgentEvent.of(
                nextSeq(), Math.max(0L, revisionSupplier.getAsLong()), sessionId, runId, type, payload);
        for (AgentEventSubscriber subscriber : subscribers) {
            try {
                subscriber.send(event);
            } catch (IOException e) {
                subscribers.remove(subscriber);
                subscriber.close();
            }
        }
    }
}
