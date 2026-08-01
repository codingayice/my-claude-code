package cn.ayice.veyra.memory;

/**
 * 显式记忆操作的统一结果，确保调用方只在持久化成功后声明完成。
 */
public record MemoryOperationResult(
        boolean success,
        boolean partial,
        MemoryErrorCode errorCode,
        String message,
        MemoryEntry entry
) {
    /**
     * 创建成功结果。
     */
    public static MemoryOperationResult success(String message, MemoryEntry entry) {
        return new MemoryOperationResult(true, false, null, message, entry);
    }

    /**
     * 创建失败结果。
     */
    public static MemoryOperationResult failure(MemoryErrorCode code, String message) {
        return new MemoryOperationResult(false, false, code, message, null);
    }

    /**
     * 创建 topic 已落盘但派生索引需要修复的部分成功结果。
     */
    public static MemoryOperationResult partial(MemoryErrorCode code, String message, MemoryEntry entry) {
        return new MemoryOperationResult(false, true, code, message, entry);
    }
}
