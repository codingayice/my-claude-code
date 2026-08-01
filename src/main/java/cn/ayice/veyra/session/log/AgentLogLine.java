package cn.ayice.veyra.session.log;

/**
 * 一条已经由 Logback 按控制台格式排版好的日志行。
 */
public record AgentLogLine(long seq, long timestampMs, String line) {
}
