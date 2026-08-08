package cn.ayice.veyra.compaction;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 当前 Session Runtime 的摘要状态边界，集中定义候选、已提交快照和提交结果。
 */
public final class SessionSummaryState implements AutoCloseable {

    private SummarySnapshot current;
    private final Consumer<SummarySnapshot> persistence;
    private boolean closed;

    public SessionSummaryState() {
        this(null, summary -> { });
    }

    /**
     * 使用可选恢复值和先落盘后发布的持久化回调创建状态边界。
     */
    public SessionSummaryState(SummarySnapshot restored, Consumer<SummarySnapshot> persistence) {
        this.current = restored;
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    /**
     * 返回当前已提交摘要快照；没有有效值时返回空。
     */
    public synchronized Optional<SummarySnapshot> current() {
        return Optional.ofNullable(current);
    }

    /**
     * 按 coveredSequence 单调提交候选，并在锁内分配运行期版本。
     */
    public synchronized CommitResult commit(SummaryCandidate candidate) {
        if (closed) {
            return new CommitResult(CommitStatus.SKIPPED_CLOSED, Optional.empty());
        }
        if (current != null && candidate.coveredSequence() <= current.coveredSequence()) {
            return new CommitResult(CommitStatus.SKIPPED_OLDER_COVERAGE, Optional.of(current));
        }
        long version = current == null ? 1 : current.summaryVersion() + 1;
        SummarySnapshot next = new SummarySnapshot(candidate.summaryText(), candidate.coveredSequence(), version);
        // 持久化失败时异常向上传播，current 保持旧值。
        persistence.accept(next);
        current = next;
        return new CommitResult(CommitStatus.COMMITTED, Optional.of(current));
    }

    /**
     * 关闭后拒绝新候选并清理当前摘要快照。
     */
    @Override
    public synchronized void close() {
        closed = true;
        current = null;
    }

    /**
     * 尚未提交的会话摘要候选。
     */
    public record SummaryCandidate(String summaryText, long coveredSequence) {
        public SummaryCandidate {
            summaryText = Objects.requireNonNull(summaryText, "summaryText").trim();
            if (summaryText.isEmpty()) {
                throw new IllegalArgumentException("summaryText must not be blank");
            }
            if (coveredSequence <= 0) {
                throw new IllegalArgumentException("coveredSequence must be positive");
            }
        }
    }

    /**
     * 当前活跃 Session 已提交的不可变会话摘要快照。
     */
    public record SummarySnapshot(String summaryText, long coveredSequence, long summaryVersion) {
    }

    /**
     * 会话摘要候选的原子提交状态。
     */
    public enum CommitStatus {
        COMMITTED,
        SKIPPED_OLDER_COVERAGE,
        SKIPPED_CLOSED
    }

    /**
     * 携带提交状态及提交后当前摘要；关闭状态下摘要为空。
     */
    public record CommitResult(CommitStatus status, Optional<SummarySnapshot> summary) {
    }
}
