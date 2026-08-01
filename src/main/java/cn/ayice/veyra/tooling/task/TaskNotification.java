package cn.ayice.veyra.tooling.task;

/**
 * 异步任务完成或状态变化时交给主 agent 的通知。主 agent 可读取它，把后台结果纳入当前上下文。
 */
public record TaskNotification(
        String taskId,
        String taskType,
        String status,
        String content
) {
    /**
     * 根据输入创建对应对象。
     */
    public static TaskNotification of(String taskId, String taskType, String status, String content) {
        return new TaskNotification(taskId, taskType, status, content == null ? "" : content);
    }
}
