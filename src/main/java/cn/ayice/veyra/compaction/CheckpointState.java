package cn.ayice.veyra.compaction;

import java.util.Objects;
import java.util.Optional;

/**
 * 当前 Session Runtime 的 checkpoint 状态边界，集中定义候选、已提交值和提交结果。
 */
public final class CheckpointState implements AutoCloseable {

    private Checkpoint current;
    private boolean closed;

    /**
     * 返回当前已提交 checkpoint；没有有效值时返回空。
     */
    public synchronized Optional<Checkpoint> current() {
        return Optional.ofNullable(current);
    }

    /**
     * 按 coveredSequence 单调提交候选，并在锁内分配运行期版本。
     */
    public synchronized CommitResult commit(Candidate candidate) {
        if (closed) {
            return new CommitResult(CommitStatus.SKIPPED_CLOSED, Optional.empty());
        }
        if (current != null && candidate.coveredSequence() <= current.coveredSequence()) {
            return new CommitResult(CommitStatus.SKIPPED_OLDER_COVERAGE, Optional.of(current));
        }
        long version = current == null ? 1 : current.checkpointVersion() + 1;
        current = new Checkpoint(candidate.summaryText(), candidate.coveredSequence(), version);
        return new CommitResult(CommitStatus.COMMITTED, Optional.of(current));
    }

    /**
     * 关闭后拒绝新候选并清理当前 checkpoint。
     */
    @Override
    public synchronized void close() {
        closed = true;
        current = null;
    }

    /**
     * 尚未提交的会话摘要候选。
     */
    public record Candidate(String summaryText, long coveredSequence) {
        public Candidate {
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
     * 当前活跃 Session 已提交的不可变压缩检查点。
     */
    public record Checkpoint(String summaryText, long coveredSequence, long checkpointVersion) {
    }

    /**
     * checkpoint 候选的原子提交状态。
     */
    public enum CommitStatus {
        COMMITTED,
        SKIPPED_OLDER_COVERAGE,
        SKIPPED_CLOSED
    }

    /**
     * 携带提交状态及提交后当前 checkpoint；关闭状态下 checkpoint 为空。
     */
    public record CommitResult(CommitStatus status, Optional<Checkpoint> checkpoint) {
    }
}
