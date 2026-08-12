package cn.ayice.veyra.runtime.session;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.HashSet;
import java.util.Set;

/**
 * 将同一会话的 Run 串行化，同时允许不同会话共享线程池并行执行。
 */
final class SessionRunQueue {

    private final Executor executor;
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    private final Set<String> scheduledRuns = new HashSet<>();

    /**
     * 使用共享 Run 执行器创建会话私有队列。
     */
    SessionRunQueue(Executor executor) {
        this.executor = executor;
    }

    /**
     * 把任务链接到当前队尾，并返回可观察本次任务完成状态的 Future。
     */
    synchronized CompletableFuture<Void> submit(Runnable task) {
        // handle 吞掉前一项的完成状态，仅用于保证前一项失败后队列仍能继续消费后续 Run。
        CompletableFuture<Void> next = tail
                .handle((ignored, failure) -> null)
                .thenRunAsync(task, executor);
        tail = next;
        return next;
    }

    /** 对同一 runId 只保留一个 queued/running 推进任务。 */
    synchronized CompletableFuture<Void> submit(String runId, Runnable task) {
        if (!scheduledRuns.add(runId)) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> next = tail.handle((ignored, failure) -> null).thenRunAsync(() -> {
            try { task.run(); }
            finally { synchronized (SessionRunQueue.this) { scheduledRuns.remove(runId); } }
        }, executor);
        tail = next;
        return next;
    }
}
