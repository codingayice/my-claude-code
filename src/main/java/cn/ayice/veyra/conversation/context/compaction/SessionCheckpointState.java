package cn.ayice.veyra.conversation.context.compaction;

import java.util.Optional;

/**
 * 当前 Session Runtime 内 checkpoint 的唯一读取和提交边界。
 */
public final class SessionCheckpointState implements AutoCloseable {

    private CompactionCheckpoint current;
    private boolean closed;

    /**
     * 返回当前已提交 checkpoint；没有有效值时返回空。
     */
    public synchronized Optional<CompactionCheckpoint> current() {
        return Optional.ofNullable(current);
    }

    /**
     * 按 coveredSequence 单调提交候选，并在锁内分配运行期版本。
     */
    public synchronized CommitResult commit(CheckpointCandidate candidate) {
        if (closed) {
            return new CommitResult(CommitStatus.SKIPPED_CLOSED, Optional.empty());
        }
        if (current != null && candidate.coveredSequence() <= current.coveredSequence()) {
            return new CommitResult(CommitStatus.SKIPPED_OLDER_COVERAGE, Optional.of(current));
        }
        long version = current == null ? 1 : current.checkpointVersion() + 1;
        current = new CompactionCheckpoint(candidate.summaryText(), candidate.coveredSequence(), version);
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
     * checkpoint 候选的原子提交结果，用于区分正常提交、旧覆盖和会话关闭。
     */
    public enum CommitStatus {
        COMMITTED,
        SKIPPED_OLDER_COVERAGE,
        SKIPPED_CLOSED
    }

    /**
     * 携带提交状态及提交后当前 checkpoint；关闭状态下 checkpoint 为空。
     */
    public record CommitResult(CommitStatus status, Optional<CompactionCheckpoint> checkpoint) {
    }
}
