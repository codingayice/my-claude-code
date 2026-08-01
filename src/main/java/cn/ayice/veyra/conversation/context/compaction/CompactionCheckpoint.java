package cn.ayice.veyra.conversation.context.compaction;

/**
 * 当前活跃 Session 已提交的不可变压缩检查点。
 */
public record CompactionCheckpoint(String summaryText, long coveredSequence, long checkpointVersion) {
}
