package cn.ayice.veyra.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Map;

/**
 * Agent 与 Chat 两种执行策略共用的 Run 生命周期协调器。
 */
public class RunCoordinator {

    private static final Logger log = LoggerFactory.getLogger(RunCoordinator.class);

    /**
     * 绑定日志和事件上下文，执行目标策略，并在统一边界转换未处理异常。
     */
    public void execute(RunTarget target, RunCommand command) {
        execute(target, command, false);
    }

    /** 推进新 Run 或恢复同一个已受理 Run。 */
    public void execute(RunTarget target, RunCommand command, boolean resume) {
        // MDC 与事件流使用同一组标识，保证后端日志、SSE 和前端消息可以交叉定位。
        MDC.put("sessionId", command.sessionId());
        MDC.put("runId", command.runId());
        target.bindRun(command.runId());
        if (!resume) target.emit("run.started", Map.of(
                "input", command.input(), "runId", command.runId(), "mode", command.mode().name().toLowerCase()));

        try {
            // RunMode 只在协调层分流，AgentLoop 和 ChatLoop 内部不再携带模式分支。
            if (command.mode() == RunMode.CHAT) {
                target.executeChat(command.input());
            } else {
                cn.ayice.veyra.runtime.agent.AgentStepResult result = resume
                        ? target.resumeAgent() : target.executeAgent(command.input());
                if ("suspended".equals(result.status())) return;
                if ("failed".equals(result.status())) {
                    target.failRun(Map.of("reason", result.reason(), "content",
                            String.valueOf(result.output().getOrDefault("content", ""))));
                    return;
                }
            }
            target.completeRun(Map.of("reason", "completed"));
        } catch (Exception e) {
            // Run 边界记录完整堆栈，对外事件只发送稳定且可展示的错误摘要。
            String message = safeErrorMessage(e);
            log.error("run failed sessionId={} runId={}", command.sessionId(), command.runId(), e);
            target.emit("run.failed", Map.of(
                    "content", message,
                    "error", message,
                    "runId", command.runId()
            ));
            target.failRun(Map.of("reason", "unhandled_exception", "content", message));
        } finally {
            MDC.remove("runId");
            MDC.remove("sessionId");
        }
    }

    /**
     * 返回适合写入失败事件的错误摘要，避免空异常消息破坏前端展示。
     */
    private static String safeErrorMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "请求处理失败";
        }
        return e.getMessage();
    }
}
