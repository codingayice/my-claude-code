package cn.ayice.veyra.memory;

/**
 * 长期记忆的语义类型，用于约束保存内容并辅助后续召回。
 */
public enum MemoryType {
    PREFERENCE,
    FEEDBACK,
    CONTEXT,
    REFERENCE
}
