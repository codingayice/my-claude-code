package cn.ayice.veyra.tooling.task;

/**
 * 异步任务状态枚举。它给后台命令和子 agent 任务提供统一的前后端状态值。
 */
public enum TaskStatus {
    PENDING("pending"),
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    KILLED("killed");

    private final String wireValue;

    TaskStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * 返回任务状态在事件和接口中的稳定字符串值。
     */
    public String wireValue() {
        return wireValue;
    }

    /**
     * 判断循环是否已完成、失败或取消，不能再进入下一轮。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == KILLED;
    }

    /**
     * 根据输入创建对应对象。
     */
    public static TaskStatus fromWireValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return FAILED;
        }
        return switch (raw.toLowerCase()) {
            case "pending" -> PENDING;
            case "running" -> RUNNING;
            case "completed" -> COMPLETED;
            case "failed", "error" -> FAILED;
            case "killed", "stopped" -> KILLED;
            default -> FAILED;
        };
    }
}
