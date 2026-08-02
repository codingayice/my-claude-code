package cn.ayice.veyra.runtime;

import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.subagent.AgentProfile;
import cn.ayice.veyra.subagent.SubagentRuntime;
import cn.ayice.veyra.subagent.AgentRunResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * 单个会话的后台长期记忆提取协调器，提供 single-flight、尾随合并和失败游标保护。
 */
public class MemoryExtractionCoordinator {

    private static final Logger log = LoggerFactory.getLogger(MemoryExtractionCoordinator.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int MAX_MESSAGE_CHARS = 2_000;

    private final Object monitor = new Object();
    private final String sessionId;
    private final MemoryService memoryService;
    private final SubagentRuntime runtime;
    private final Executor executor;
    private final int maxRounds;

    private int cursor;
    private boolean running;
    private Request pending;
    private Instant lastCompletedAt;
    private String lastResult = "never";
    private String lastErrorCode;

    /**
     * 使用会话标识、统一记忆服务、受限子 Agent 和受管执行器创建协调器。
     */
    public MemoryExtractionCoordinator(
            String sessionId,
            MemoryService memoryService,
            SubagentRuntime runtime,
            Executor executor,
            int maxRounds
    ) {
        this.sessionId = sessionId == null || sessionId.isBlank() ? "unknown-session" : sessionId;
        this.memoryService = memoryService;
        this.runtime = runtime;
        this.executor = executor;
        this.maxRounds = maxRounds <= 0 ? 5 : Math.min(maxRounds, 5);
    }

    /**
     * 提交最新消息快照。已有任务运行时只保留最新快照，当前任务结束后执行一次尾随提取。
     */
    public void submit(List<ChatMessage> messages, boolean mainAgentWroteMemory) {
        if (memoryService == null || runtime == null || executor == null || messages == null || !memoryService.isEnabled()) {
            return;
        }
        synchronized (monitor) {
            if (mainAgentWroteMemory) {
                // 主 Agent 已经显式处理当前区间，推进游标避免后台重复写入。
                cursor = Math.max(cursor, messages.size());
                pending = null;
                lastResult = "skipped-explicit-write";
                lastCompletedAt = Instant.now();
                return;
            }
            if (messages.size() <= cursor) {
                return;
            }
            pending = new Request(sessionId, cursor, messages);
            if (!running) {
                running = true;
                executor.execute(this::drain);
            }
        }
    }

    /**
     * 同步执行一次提取，主要供边界测试和关闭前诊断使用。
     */
    public boolean extractNow(List<ChatMessage> messages) {
        if (memoryService == null || runtime == null || messages == null || messages.size() <= cursor
                || !memoryService.isEnabled()) {
            return false;
        }
        return execute(new Request(sessionId, cursor, messages));
    }

    /**
     * 判断主 Agent 工具请求是否已经显式修改长期记忆。
     */
    public boolean isMemoryWriteRequest(ToolExecutionRequest request) {
        if (request == null || !"Memory".equals(request.name())) {
            return false;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(request.arguments() == null ? "{}" : request.arguments());
            String action = root.path("action").asText("");
            return "remember".equals(action) || "forget".equals(action);
        } catch (Exception invalidArguments) {
            log.debug("无法识别 Memory 工具参数, toolUseId={}", request.id(), invalidArguments);
            return false;
        }
    }

    /**
     * 返回当前协调器的不可变诊断状态。
     */
    public Status status() {
        synchronized (monitor) {
            return new Status(
                    cursor,
                    running,
                    pending != null,
                    lastCompletedAt,
                    lastResult,
                    lastErrorCode
            );
        }
    }

    /**
     * 在给定超时内等待运行中和待处理任务排空。
     */
    public boolean awaitIdle(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (monitor) {
            while (running || pending != null) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    long millis = Math.max(1L, Duration.ofNanos(remaining).toMillis());
                    monitor.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 顺序消费当前请求和最后一个尾随请求；任何时刻最多有一个 drain 运行。
     */
    private void drain() {
        while (true) {
            Request request;
            synchronized (monitor) {
                request = pending;
                pending = null;
                if (request == null) {
                    running = false;
                    monitor.notifyAll();
                    return;
                }
            }
            execute(request);
        }
    }

    /**
     * 运行受限提取 Subagent；只有 completed 才推进游标，失败保留待处理区间。
     */
    private boolean execute(Request request) {
        if (request.messages().size() <= request.fromMessageIndex()) {
            return true;
        }
        try {
            AgentRunResult result = runtime.run(
                    AgentProfile.memoryExtraction(maxRounds),
                    buildPrompt(request),
                    null,
                    "memory-" + UUID.randomUUID().toString().substring(0, 8),
                    "长期记忆提取"
            );
            boolean success = "completed".equals(result.status());
            synchronized (monitor) {
                if (success) {
                    cursor = Math.max(cursor, request.messages().size());
                    lastResult = "success";
                    lastErrorCode = null;
                } else {
                    lastResult = result.status();
                    lastErrorCode = "MEMORY_EXTRACTION_FAILED";
                }
                lastCompletedAt = Instant.now();
            }
            if (!success) {
                log.warn("长期记忆提取未完成, sessionId={}, cursor={}, status={}",
                        sessionId, request.fromMessageIndex(), result.status());
            }
            return success;
        } catch (Exception error) {
            synchronized (monitor) {
                lastCompletedAt = Instant.now();
                lastResult = "failed";
                lastErrorCode = "MEMORY_EXTRACTION_FAILED";
            }
            log.error("长期记忆提取失败, sessionId={}, cursor={}, code=MEMORY_EXTRACTION_FAILED",
                    sessionId, request.fromMessageIndex(), error);
            return false;
        }
    }

    /**
     * 只把游标之后的用户和助手自然语言交给提取器，工具流水不进入长期记忆候选。
     */
    private static String buildPrompt(Request request) {
        int start = Math.min(request.fromMessageIndex(), request.messages().size());
        List<String> conversation = new ArrayList<>();
        for (ChatMessage message : request.messages().subList(start, request.messages().size())) {
            if (message instanceof UserMessage userMessage) {
                conversation.add("用户: " + limit(safeUserText(userMessage), MAX_MESSAGE_CHARS));
            } else if (message instanceof AiMessage aiMessage && aiMessage.text() != null && !aiMessage.text().isBlank()) {
                conversation.add("Veyra: " + limit(aiMessage.text(), MAX_MESSAGE_CHARS));
            }
        }
        String lines = conversation.stream()
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"));
        return """
                检查下面的新对话片段是否包含未来新会话仍有价值的长期信息。
                先使用 Memory.list/show 检查已有记忆；优先更新已有 topic，避免重复创建。
                只保存稳定用户偏好、Agent 行为反馈、无法从代码和 Git 推导的项目背景，以及外部参考入口。
                不保存 transcript、压缩摘要、当前任务进度、Todo、工具输出、文件或函数清单、Git 历史和敏感信息。
                自动提取一律使用 RELEVANT；无法判断 USER 或 PROJECT 时不要保存。
                没有可保存内容时不要调用 remember，直接结束。

                新对话片段:
                %s
                """.formatted(lines).trim();
    }

    /**
     * 提取纯文本用户内容；多模态消息不适合作为当前提取器输入时安全跳过。
     */
    private static String safeUserText(UserMessage message) {
        try {
            return message.singleText();
        } catch (Exception unsupportedContent) {
            return "";
        }
    }

    /**
     * 限制单条自然语言进入提取提示词的字符数，防止后台任务失控膨胀。
     */
    private static String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxChars) + "\n[消息已截断]";
    }

    /**
     * 一次不可变的长期记忆提取快照。
     */
    private record Request(String sessionId, int fromMessageIndex, List<ChatMessage> messages) {
        private Request {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }

    /**
     * 当前会话后台长期记忆提取的可诊断状态。
     */
    public record Status(
            int cursor,
            boolean running,
            boolean pending,
            Instant lastCompletedAt,
            String lastResult,
            String lastErrorCode
    ) {
    }
}
