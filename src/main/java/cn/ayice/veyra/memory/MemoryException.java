package cn.ayice.veyra.memory;

/**
 * 记忆模块的类型化异常。对外只使用安全消息，完整原因由日志记录。
 */
public class MemoryException extends RuntimeException {

    private final Code code;

    /**
     * 使用稳定错误码和安全消息创建异常。
     */
    public MemoryException(Code code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用稳定错误码、安全消息和底层原因创建异常。
     */
    public MemoryException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 返回稳定错误码。
     */
    public Code code() {
        return code;
    }

    /**
     * 记忆模块对日志、工具和命令稳定暴露的错误码。
     */
    public enum Code {
        MEMORY_INVALID_REQUEST,
        MEMORY_SENSITIVE_CONTENT,
        MEMORY_NOT_FOUND,
        MEMORY_READ_FAILED,
        MEMORY_WRITE_FAILED,
        MEMORY_INDEX_REBUILD_FAILED,
        MEMORY_BUDGET_EXCEEDED
    }
}
