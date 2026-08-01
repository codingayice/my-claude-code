package cn.ayice.veyra.tooling.task;


import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.task.TaskNotification;
import cn.ayice.veyra.tooling.task.TaskStatus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 用户可见子 agent 的任务管理器。它负责同步或后台运行 SubagentRuntime，并把完成结果整理给主 agent 使用。
 */
public class AgentTaskManager {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskManager.class);

    private static final int RESULT_PREVIEW_LIMIT = 3000;
    private static final List<String> SUBAGENT_NAMES = List.of(
            "青砚",
            "星河",
            "云舟",
            "墨衡",
            "北辰",
            "听澜",
            "灵犀",
            "白泽",
            "扶光",
            "知微",
            "澄明",
            "归舟"
    );

    private final SubagentExecution runtime;
    private final BiConsumer<String, Map<String, Object>> eventSink;
    private final Executor executor;
    private final ConcurrentHashMap<String, AgentTask> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<TaskNotification> notifications = new ConcurrentLinkedDeque<>();
    private final AtomicInteger nextSubagentNameIndex = new AtomicInteger(0);

    /**
     * 使用子 Agent 执行入口和任务线程池创建无事件输出的任务管理器。
     */
    public AgentTaskManager(SubagentExecution runtime, Executor executor) {
        this(runtime, executor, null);
    }

    /**
     * 使用子 Agent 执行入口、任务线程池和可选事件输出创建任务管理器。
     */
    public AgentTaskManager(
            SubagentExecution runtime,
            Executor executor,
            BiConsumer<String, Map<String, Object>> eventSink
    ) {
        this.runtime = runtime;
        this.executor = executor;
        this.eventSink = eventSink;
    }

    /**
     * 创建并异步执行一个子 Agent 任务，立即返回可查询的 agentId。
     */
    public String submit(String description, String prompt, String subagentType, PermissionContext permissionContext) {
        String agentId = UUID.randomUUID().toString().substring(0, 8);
        AgentTask task = new AgentTask(agentId, nextSubagentName(), description, prompt, subagentType);
        tasks.put(agentId, task);
        emitTaskEvent("task.started", task, null, null);

        Future<?> future = CompletableFuture.runAsync(() -> {
            try {
                AgentRunResult result = runtime.run(prompt, subagentType, permissionContext, agentId, description);
                // stop_task 可能先把状态改为 KILLED；迟到的完成结果不得覆盖用户取消决定。
                if (task.status != TaskStatus.RUNNING) {
                    return;
                }
                task.status = TaskStatus.fromWireValue(result.status());
                task.result = result;
                notifications.add(notification(task, result));
                emitTaskEvent(eventTypeFor(result.status()), task, result, null);
            } catch (Exception e) {
                log.error("Subagent task failed agentId={} type={}", agentId, subagentType, e);
                // 被取消任务产生的中断异常不再重复发布 failed 事件。
                if (task.status != TaskStatus.RUNNING) {
                    return;
                }
                task.status = TaskStatus.FAILED;
                task.error = e.getMessage();
                AgentRunResult result = new AgentRunResult(
                        agentId,
                        normalizeType(subagentType),
                        "failed",
                        "子 agent 执行失败: " + e.getMessage(),
                        0,
                        0
                );
                task.result = result;
                notifications.add(notification(task, result));
                emitTaskEvent("task.failed", task, result, e.getMessage());
            }
        }, executor);
        task.future = future;
        return agentId;
    }

    /**
     * 在当前线程执行子 Agent，同时沿用异步任务相同的状态和通知协议。
     */
    public AgentRunResult runSync(String description, String prompt, String subagentType, PermissionContext permissionContext) {
        String agentId = UUID.randomUUID().toString().substring(0, 8);
        AgentTask task = new AgentTask(agentId, nextSubagentName(), description, prompt, subagentType);
        tasks.put(agentId, task);
        emitTaskEvent("task.started", task, null, null);

        try {
            AgentRunResult result = runtime.run(prompt, subagentType, permissionContext, agentId, description);
            if (task.status == TaskStatus.RUNNING) {
                task.status = TaskStatus.fromWireValue(result.status());
                task.result = result;
                notifications.add(notification(task, result));
                emitTaskEvent(eventTypeFor(result.status()), task, result, null);
            }
            return result;
        } catch (Exception e) {
            log.error("Synchronous subagent task failed agentId={} type={}", agentId, subagentType, e);
            task.status = TaskStatus.FAILED;
            task.error = e.getMessage();
            AgentRunResult result = new AgentRunResult(
                    agentId,
                    normalizeType(subagentType),
                    "failed",
                    "子 agent 执行失败: " + e.getMessage(),
                    0,
                    0
            );
            task.result = result;
            notifications.add(notification(task, result));
            emitTaskEvent("task.failed", task, result, e.getMessage());
            return result;
        }
    }

    /**
     * 返回指定任务详情；未指定 agentId 时返回全部任务摘要。
     */
    public String check(String agentId) {
        if (agentId != null && !agentId.isBlank()) {
            AgentTask task = tasks.get(agentId);
            if (task == null) {
                return "未找到的 agent 任务: " + agentId;
            }
            if (task.result != null) {
                return formatResult(task.result);
            }
            if (task.error != null) {
                return "[" + task.status.wireValue() + "] " + task.error;
            }
            return "[" + task.status.wireValue() + "] " + task.description;
        }
        if (tasks.isEmpty()) {
            return "没有 agent 任务。";
        }
        return tasks.values().stream()
                .map(task -> "- %s: [%s] %s".formatted(
                        task.agentId,
                        task.status.wireValue(),
                        task.description
                ))
                .collect(Collectors.joining("\n"))
                .trim();
    }

    /**
     * 取消仍在运行的子 Agent，并立即发布 KILLED 状态和通知。
     */
    public boolean cancel(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            return false;
        }
        AgentTask task = tasks.get(agentId);
        if (task == null || task.status != TaskStatus.RUNNING) {
            return false;
        }

        // 先取消 Future，再固定任务状态；异步回调会检查 RUNNING，避免覆盖 KILLED。
        Future<?> future = task.future;
        boolean cancelled = future != null && future.cancel(true);
        task.status = TaskStatus.KILLED;
        AgentRunResult result = new AgentRunResult(
                task.agentId,
                normalizeType(task.subagentType),
                "killed",
                "子 agent 在完成前被停止。",
                0,
                0
        );
        task.result = result;
        notifications.add(notification(task, result));
        emitTaskEvent("task.killed", task, result, null);
        return cancelled || future == null;
    }

    /**
     * 原子取走目前已完成任务产生的全部通知。
     */
    public List<TaskNotification> drain() {
        List<TaskNotification> out = new ArrayList<>();
        TaskNotification item;
        while ((item = notifications.poll()) != null) {
            out.add(item);
        }
        return out;
    }

    /**
     * 等待本轮已启动的全部子 Agent 结束，再一次性返回完成通知。
     */
    public List<TaskNotification> awaitRunningAndDrain() {
        return drain();
    }

    /**
     * 判断给定标识对应的任务是否存在。
     */
    public boolean hasTask(String agentId) {
        return agentId != null && !agentId.isBlank() && tasks.containsKey(agentId);
    }

    /**
     * 判断是否仍有尚未结束的子 Agent 任务。
     */
    public boolean hasRunning() {
        return tasks.values().stream().anyMatch(task -> task.status == TaskStatus.RUNNING);
    }

    /**
     * 返回当前仍处于运行状态的子 Agent 数量。
     */
    public int runningCount() {
        return (int) tasks.values().stream().filter(task -> task.status == TaskStatus.RUNNING).count();
    }

    /**
     * 停止当前组件并释放其后台资源。
     */
    public void shutdown() {
        tasks.values().stream()
                .map(task -> task.future)
                .filter(java.util.Objects::nonNull)
                .forEach(future -> future.cancel(true));
    }

    /**
     * 根据任务终态创建一次性主 Agent 通知。
     */
    private TaskNotification notification(AgentTask task, AgentRunResult result) {
        return TaskNotification.of(task.agentId, "subagent", normalizeStatus(result.status()), formatTaskNotification(task, result));
    }

    /**
     * 将输入格式化为任务任务通知。
     */
    private String formatTaskNotification(AgentTask task, AgentRunResult result) {
        String status = normalizeStatus(result.status());
        String summary = "Agent \"" + task.description + "\" " + summaryVerb(status) + "。";
        return """
                <task-notification>
                  <task-id>%s</task-id>
                  <agent-type>%s</agent-type>
                  <description>%s</description>
                  <status>%s</status>
                  <summary>%s</summary>
                  <result>%s</result>
                  <usage>{"totalDurationMs":%d,"totalToolUseCount":%d}</usage>
                </task-notification>
                """.formatted(
                escapeXml(task.agentId),
                escapeXml(result.agentType()),
                escapeXml(task.description),
                escapeXml(status),
                escapeXml(summary),
                escapeXml(truncate(result.content(), RESULT_PREVIEW_LIMIT)),
                result.totalDurationMs(),
                result.totalToolUseCount()
        ).trim();
    }

    /**
     * 将输入格式化为结果。
     */
    private String formatResult(AgentRunResult result) {
        return "Agent " + result.agentId() + " [" + result.agentType() + "/" + result.status() + "]\n"
                + "耗时: " + result.totalDurationMs() + " ms\n"
                + "工具调用次数: " + result.totalToolUseCount() + "\n\n"
                + result.content();
    }

    /**
     * 按最大长度截断文本，并保留明确的截断标记。
     */
    private String truncate(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLen) + "\n\n[... 内容已截断，完整结果长度 " + text.length() + " 字符 ...]";
    }

    /**
     * 将类型规范化为内部统一形式。
     */
    private String normalizeType(String raw) {
        return raw == null || raw.isBlank() ? "general-purpose" : raw;
    }

    /**
     * 将状态规范化为内部统一形式。
     */
    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "failed";
        }
        return switch (status) {
            case "error" -> "failed";
            default -> status;
        };
    }

    /**
     * 把任务终态映射为通知摘要中使用的动词。
     */
    private String summaryVerb(String status) {
        return switch (status) {
            case "completed" -> "已完成";
            case "failed" -> "已失败";
            case "killed" -> "已被停止";
            default -> "结束，状态为 " + status;
        };
    }

    /**
     * 处理并传播 {@code emitTaskEvent} 对应的事件。
     */
    private void emitTaskEvent(String type, AgentTask task, AgentRunResult result, String error) {
        if (eventSink == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.agentId);
        payload.put("taskType", "subagent");
        payload.put("name", task.name);
        payload.put("description", task.description);
        payload.put("subagentType", normalizeType(task.subagentType));
        payload.put("status", result == null ? task.status.wireValue() : normalizeStatus(result.status()));
        if (result != null) {
            payload.put("totalDurationMs", result.totalDurationMs());
            payload.put("totalToolUseCount", result.totalToolUseCount());
            payload.put("content", truncate(result.content(), RESULT_PREVIEW_LIMIT));
        }
        if (error != null && !error.isBlank()) {
            payload.put("error", error);
        }
        eventSink.accept(type, payload);
    }

    /**
     * 将任务终态映射为对应的事件类型。
     */
    private String eventTypeFor(String status) {
        return switch (normalizeStatus(status)) {
            case "completed" -> "task.completed";
            case "killed" -> "task.killed";
            default -> "task.failed";
        };
    }

    /**
     * 按循环序列分配下一个可读子 Agent 名称。
     */
    String nextSubagentName() {
        int sequence = nextSubagentNameIndex.getAndIncrement();
        String baseName = SUBAGENT_NAMES.get(sequence % SUBAGENT_NAMES.size());
        int cycle = sequence / SUBAGENT_NAMES.size();
        return cycle == 0 ? baseName : baseName + "-" + (cycle + 1);
    }

    /**
     * 转义任务通知中的 XML 特殊字符。
     */
    private String escapeXml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /**
     * 子 Agent 任务的标识、输入、状态、Future 和结果。
     */
    private static final class AgentTask {
        private final String agentId;
        private final String name;
        private final String description;
        private final String prompt;
        private final String subagentType;
        private volatile TaskStatus status = TaskStatus.RUNNING;
        private volatile String error;
        private volatile AgentRunResult result;
        private volatile Future<?> future;

        private AgentTask(String agentId, String name, String description, String prompt, String subagentType) {
            this.agentId = agentId;
            this.name = name;
            this.description = description;
            this.prompt = prompt;
            this.subagentType = subagentType;
        }
    }
}
