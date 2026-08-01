package cn.ayice.veyra.compaction;

/**
 * 一次上下文准备过程中实际生效的压缩级别。
 */
public enum CompactStrategy {
    NONE,
    MICRO,
    SESSION_SUMMARY,
    LLM_SUMMARY
}
