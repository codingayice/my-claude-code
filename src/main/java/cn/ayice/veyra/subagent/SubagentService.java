package cn.ayice.veyra.subagent;

import cn.ayice.veyra.tool.background.TaskNotification;
import cn.ayice.veyra.tool.permission.PermissionContext;

import java.util.List;

/**
 * 子 Agent 能力的统一入口，集中提供同步执行、后台提交、查询、取消和通知收集。
 */
public final class SubagentService {

    private final AgentTaskManager tasks;

    /**
     * 使用子 Agent 任务管理器创建能力服务。
     */
    public SubagentService(AgentTaskManager tasks) {
        this.tasks = tasks;
    }

    /**
     * 异步提交子 Agent 任务并返回任务标识。
     */
    public String submit(
            String description,
            String prompt,
            String subagentType,
            PermissionContext permissionContext
    ) {
        return tasks.submit(description, prompt, subagentType, permissionContext);
    }

    /**
     * 同步执行子 Agent 并返回结构化结果。
     */
    public AgentRunResult runSync(
            String description,
            String prompt,
            String subagentType,
            PermissionContext permissionContext
    ) {
        return tasks.runSync(description, prompt, subagentType, permissionContext);
    }

    /**
     * 返回指定任务或全部任务的当前状态文本。
     */
    public String check(String taskId) {
        return tasks.check(taskId);
    }

    /**
     * 取消指定任务；任务不存在或已经终止时返回 false。
     */
    public boolean cancel(String taskId) {
        return tasks.cancel(taskId);
    }

    /**
     * 返回是否存在指定子 Agent 任务。
     */
    public boolean hasTask(String taskId) {
        return tasks.hasTask(taskId);
    }

    /**
     * 排空已经完成但尚未注入主 Agent 的任务通知。
     */
    public List<TaskNotification> drainNotifications() {
        return tasks.drain();
    }

    /**
     * 取消运行任务并释放该会话持有的子 Agent 状态。
     */
    public void shutdown() {
        tasks.shutdown();
    }
}
