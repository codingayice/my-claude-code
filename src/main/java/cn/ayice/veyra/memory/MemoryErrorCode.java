package cn.ayice.veyra.memory;

/**
 * 记忆模块对日志、工具和命令稳定暴露的错误码。
 */
public enum MemoryErrorCode {
    MEMORY_INVALID_REQUEST,
    MEMORY_SENSITIVE_CONTENT,
    MEMORY_NOT_FOUND,
    MEMORY_READ_FAILED,
    MEMORY_WRITE_FAILED,
    MEMORY_INDEX_REBUILD_FAILED,
    MEMORY_BUDGET_EXCEEDED
}
