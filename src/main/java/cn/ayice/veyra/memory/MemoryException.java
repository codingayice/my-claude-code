package cn.ayice.veyra.memory;

/**
 * 记忆模块的类型化异常。对外只使用安全消息，完整原因由日志记录。
 */
public class MemoryException extends RuntimeException {

    private final MemoryErrorCode code;

    /**
     * 使用稳定错误码和安全消息创建异常。
     */
    public MemoryException(MemoryErrorCode code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 使用稳定错误码、安全消息和底层原因创建异常。
     */
    public MemoryException(MemoryErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /**
     * 返回稳定错误码。
     */
    public MemoryErrorCode code() {
        return code;
    }
}
