package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.compaction.CompactionService;
import cn.ayice.veyra.compaction.CompactionService.Trigger;
import cn.ayice.veyra.compaction.CompactionService.PreparedWorkingTurn;
import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.tool.ToolResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AgentLoop 的事件门面。主循环只表达发生了什么，事件名、payload 和前端可读结构都集中在这里维护。
 */
class AgentLoopEvents {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentEventSink sink;

    AgentLoopEvents(AgentEventSink sink) {
        this.sink = sink;
    }

    /**
     * 发布用户消息进入 Agent 循环的事件。
     */
    void userMessage(String text) {
        emit("user.message", "text", text);
    }

    /**
     * 发布助手正文增量 token 事件。
     */
    void assistantToken(String token) {
        emit("assistant.token", "text", token);
    }

    /**
     * 发布一轮完整助手消息事件。
     */
    void assistantCompleted(AiMessage message) {
        String text = message.text() == null ? "" : message.text();
        emit("assistant.message.completed",
                "text", text,
                "hasToolRequests", message.hasToolExecutionRequests(),
                "outputFormat", detectOutputFormat(text)
        );
    }

    /**
     * 发布工具已进入授权阶段的事件。
     */
    void toolStarted(ToolExecutionRequest request) {
        emit("tool.call.started",
                "toolUseId", request.id(),
                "name", request.name(),
                "arguments", request.arguments()
        );
    }

    /**
     * 发布工具调用被权限系统拒绝的事件。
     */
    void toolRejected(ToolExecutionRequest request, String reason) {
        emit("tool.call.rejected",
                "toolUseId", request.id(),
                "name", request.name(),
                "reason", reason
        );
    }

    /**
     * 发布工具成功返回结果的事件。
     */
    void toolCompleted(ToolExecutionRequest request, ToolResult result, String content) {
        emit("tool.call.completed",
                "toolUseId", request.id(),
                "name", request.name(),
                "success", result.success(),
                "content", content
        );
    }

    /**
     * 发布工具执行异常事件。
     */
    void toolFailed(ToolExecutionRequest request, Exception error) {
        emit("tool.call.failed",
                "toolUseId", request.id(),
                "name", request.name(),
                "error", error.getMessage()
        );
    }

    /**
     * 发布 Run 正常完成事件及最终文本。
     */
    void runCompleted(String reason, String content) {
        emit("run.completed", "reason", reason, "content", content);
    }

    /**
     * 发布 Run 失败事件及安全错误摘要。
     */
    void runFailed(String reason, String content) {
        emit("run.failed", "reason", reason, "content", content);
    }

    /**
     * 在 token 接近限制时发布上下文容量告警。
     */
    void contextWarning(CompactionConfig.TokenState state, CompactionConfig config, String phase) {
        emit("context.warning",
                "phase", phase,
                "tokenCount", state.tokenCount(),
                "percentLeft", state.percentLeft(),
                "aboveWarning", state.aboveWarning(),
                "aboveThreshold", state.aboveThreshold(),
                "atBlockingLimit", state.atBlockingLimit(),
                "maxContextTokens", config.getMaxContextTokens(),
                "warningThreshold", config.warningThreshold(),
                "blockingLimit", config.blockingLimit(),
                "threshold", config.threshold()
        );
    }

    /**
     * 发布每次最终请求的完整输入 token 和容量区间。
     */
    void contextUsage(PreparedWorkingTurn prepared, CompactionConfig config) {
        emit("context.usage",
                "inputTokens", prepared.inputTokens(),
                "effectiveWindow", config.effectiveWindow(),
                "capacityState", prepared.capacityState()
        );
    }

    /**
     * 发布一次前台压缩成功后的策略、预算变化和摘要版本。
     */
    void compactionCompleted(CompactionService.Trigger trigger, PreparedWorkingTurn prepared, long durationMs) {
        emit("compaction.completed",
                "trigger", trigger,
                "strategy", prepared.strategy(),
                "preInputTokens", prepared.preCompactInputTokens(),
                "postInputTokens", prepared.inputTokens(),
                "tokensSaved", Math.max(0, prepared.preCompactInputTokens() - prepared.inputTokens()),
                "summaryCommit", prepared.summaryCommit(),
                "summaryVersion", prepared.summaryVersion(),
                "durationMs", durationMs
        );
    }

    /**
     * 发布前台压缩阻止模型请求时的稳定错误码和耗时。
     */
    void compactionFailed(CompactionService.Trigger trigger, String errorCode, long durationMs) {
        emit("compaction.failed",
                "trigger", trigger,
                "errorCode", errorCode,
                "durationMs", durationMs
        );
    }

    /**
     * 发布手动或自动压缩没有找到可替换旧回合时的原因。
     */
    void compactionSkipped(CompactionService.Trigger trigger, String reason) {
        emit("compaction.skipped", "trigger", trigger, "reason", reason);
    }

    /**
     * 处理并传播 {@code emit} 对应的事件。
     */
    private void emit(String type, Object... pairs) {
        sink.emit(type, eventPayload(pairs));
    }

    /**
     * 构建任务事件使用的稳定字段集合。
     */
    private static Map<String, Object> eventPayload(Object... pairs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            payload.put(String.valueOf(pairs[i]), pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return payload;
    }

    /**
     * 根据命令参数判断调用方请求的结构化输出格式。
     */
    private static String detectOutputFormat(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return "text";
        }
        try {
            OBJECT_MAPPER.readTree(trimmed);
            return "unijson";
        } catch (Exception notJson) {
            return "text";
        }
    }
}
