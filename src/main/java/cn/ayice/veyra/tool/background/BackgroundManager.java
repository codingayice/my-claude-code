package cn.ayice.veyra.tool.background;


import cn.ayice.veyra.tool.background.TaskNotification;
import cn.ayice.veyra.tool.background.TaskStatus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后台命令生命周期管理器。它并发跟踪进程、终态和一次性完成通知。
 */
public class BackgroundManager {
    private static final Logger log = LoggerFactory.getLogger(BackgroundManager.class);
    private static final int RESULT_PREVIEW_LIMIT = 3000;

    /**
     * 后台命令的进程、状态、输出和完成通知。
     */
    private static class Task {
        public final String id;
        public final String command;
        public final int timeoutSeconds;
        public volatile TaskStatus status = TaskStatus.RUNNING;
        public volatile String result;
        public volatile Process process;
        public volatile Future<?> future;

        Task(String id, String command, int timeoutSeconds) {
            this.id = id;
            this.command = command;
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<TaskNotification> notifications = new ConcurrentLinkedQueue<>();
    private final BiConsumer<String, Map<String, Object>> eventSink;
    private final Executor executor;

    /**
     * 注入状态管理所需的执行器和事件出口。
     */
    public BackgroundManager(Executor executor, BiConsumer<String, Map<String, Object>> eventSink) {
        this.executor = executor;
        this.eventSink = eventSink;
    }

    /**
     * 执行当前运行策略并返回最终结果。
     */
    public String run(String command, int timeout) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        Task task = new Task(id, command, timeout);
        tasks.put(id, task);
        emitTaskEvent("task.started", task, null, null);

        task.future = CompletableFuture.runAsync(() -> execute(task, timeout), executor);
        return "后台任务 " + id + " 已启动: " + preview(command, 60);
    }

    /**
     * 终止 {@code stop} 对应的运行资源。
     */
    public boolean stop(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        Task task = tasks.get(taskId);
        if (task == null || task.status != TaskStatus.RUNNING) {
            return false;
        }
        task.status = TaskStatus.KILLED;
        Process process = task.process;
        if (process != null) {
            process.destroyForcibly();
        }
        Future<?> future = task.future;
        if (future != null) {
            future.cancel(true);
        }
        task.result = "后台任务已停止";
        notifications.add(notification(task));
        emitTaskEvent("task.killed", task, task.result, null);
        return true;
    }

    /**
     * 等待后台进程结束或超时，并原子更新任务终态与通知。
     */
    private void execute(Task task, int timeout) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", task.command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            task.process = p;
            try (var r = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = r.read(buf)) != -1) {
                    if (task.status == TaskStatus.KILLED) {
                        break;
                    }
                    String s = new String(buf, 0, n, StandardCharsets.UTF_8);
                    output.append(s);
                    if (output.length() > 5_000_000) {
                        break;
                    }
                }
            }
            if (task.status == TaskStatus.KILLED) {
                task.result = "后台任务已停止";
                notifications.add(notification(task));
                emitTaskEvent("task.killed", task, task.result, null);
                return;
            }
            boolean done = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                task.status = TaskStatus.FAILED;
                task.result = "任务超时被终止";
                emitTaskEvent("task.failed", task, task.result, "timeout");
            } else {
                int exitCode = p.exitValue();
                if (exitCode == 0) {
                    task.status = TaskStatus.COMPLETED;
                    String text = output.toString();
                    task.result = text.length() > 50_000 ? text.substring(0, 50_000) + "\n...输出过长已截断..." : text;
                    emitTaskEvent("task.completed", task, task.result, null);
                } else {
                    task.status = TaskStatus.FAILED;
                    String text = output.toString();
                    task.result = (text.isBlank() ? "" : text + "\n") + "退出码: " + exitCode;
                    emitTaskEvent("task.failed", task, task.result, "exit_code_" + exitCode);
                }
            }
        } catch (Exception e) {
            log.error("Background task failed taskId={} command={}",
                    task.id, preview(task.command, 120), e);
            if (task.status != TaskStatus.KILLED) {
                task.status = TaskStatus.FAILED;
                task.result = "执行失败: " + e.getMessage();
                emitTaskEvent("task.failed", task, task.result, e.getMessage());
            }
        }

        notifications.add(notification(task));
    }

    /**
     * 返回后台任务当前状态和增量输出，但不移除任务记录。
     */
    public String check(String taskId) {
        if (taskId != null && !taskId.isEmpty()) {
            Task t = tasks.get(taskId);
            if (t == null) {
                return "未找到任务 " + taskId;
            }
            return "[" + t.status.wireValue() + "] " + (t.result != null ? t.result : "(运行中)");
        }
        if (tasks.isEmpty()) {
            return "当前没有后台任务";
        }
        StringBuilder sb = new StringBuilder();
        for (Task t : tasks.values()) {
            sb.append("- ").append(t.id).append(": [").append(t.status.wireValue()).append("] ").append(t.command).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 原子取走所有待发送的后台任务完成通知。
     */
    public ArrayList<TaskNotification> drain() {
        ArrayList<TaskNotification> list = new ArrayList<>();
        TaskNotification item;
        while ((item = notifications.poll()) != null) {
            list.add(item);
        }
        return list;
    }

    /**
     * 判断给定标识对应的任务是否存在。
     */
    public boolean hasTask(String taskId) {
        return taskId != null && !taskId.isBlank() && tasks.containsKey(taskId);
    }

    /**
     * 根据任务终态创建一次性主 Agent 通知。
     */
    private TaskNotification notification(Task task) {
        return TaskNotification.of(task.id, "background", task.status.wireValue(), formatTaskNotification(task));
    }

    /**
     * 处理并传播 {@code emitTaskEvent} 对应的事件。
     */
    private void emitTaskEvent(String type, Task task, String result, String error) {
        if (eventSink == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", task.id);
        payload.put("taskType", "background");
        payload.put("description", preview(task.command, 120));
        payload.put("command", task.command);
        payload.put("timeoutSeconds", task.timeoutSeconds);
        payload.put("status", task.status.wireValue());
        if (result != null) {
            payload.put("content", truncate(result, RESULT_PREVIEW_LIMIT));
        }
        if (error != null && !error.isBlank()) {
            payload.put("error", error);
        }
        eventSink.accept(type, payload);
    }

    /**
     * 将输入格式化为任务任务通知。
     */
    private String formatTaskNotification(Task task) {
        String status = task.status.wireValue();
        String summary = switch (status) {
            case "completed" -> "后台任务已完成。";
            case "failed" -> "后台任务失败。";
            case "killed" -> "后台任务已停止。";
            default -> "后台任务结束，状态为 " + status + "。";
        };
        return """
                <task-notification>
                  <task-id>%s</task-id>
                  <task-type>%s</task-type>
                  <description>%s</description>
                  <status>%s</status>
                  <summary>%s</summary>
                  <command>%s</command>
                  <timeout-seconds>%d</timeout-seconds>
                  <result>%s</result>
                </task-notification>
                """.formatted(
                escapeXml(task.id),
                escapeXml("background"),
                escapeXml(preview(task.command, 120)),
                escapeXml(status),
                escapeXml(summary),
                escapeXml(preview(task.command, 1000)),
                task.timeoutSeconds,
                escapeXml(truncate(task.result, RESULT_PREVIEW_LIMIT))
        ).trim();
    }

    /**
     * 生成长度受限、适合事件展示的输出预览。
     */
    private String preview(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLen) + "...";
    }

    /**
     * 按最大长度截断文本，并保留明确的截断标记。
     */
    private String truncate(String value, int maxLen) {
        if (value == null || value.length() <= maxLen) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLen) + "\n...output truncated...";
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
}
