package cn.ayice.veyra.tooling.task;


/**
 * 一次 SubagentRuntime 执行结束后的结构化结果。调用方通过它读取状态、最终内容、耗时和工具调用次数。
 */
public record AgentRunResult(
        String agentId,
        String agentType,
        String status,
        String content,
        long totalDurationMs,
        int totalToolUseCount
) {
}
