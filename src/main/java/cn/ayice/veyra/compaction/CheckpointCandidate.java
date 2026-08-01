package cn.ayice.veyra.compaction;

import java.util.Objects;

/**
 * 尚未提交的会话摘要候选，只携带摘要正文和真实覆盖边界。
 */
public record CheckpointCandidate(String summaryText, long coveredSequence) {
    public CheckpointCandidate {
        summaryText = Objects.requireNonNull(summaryText, "summaryText").trim();
        if (summaryText.isEmpty()) {
            throw new IllegalArgumentException("summaryText must not be blank");
        }
        if (coveredSequence <= 0) {
            throw new IllegalArgumentException("coveredSequence must be positive");
        }
    }
}
