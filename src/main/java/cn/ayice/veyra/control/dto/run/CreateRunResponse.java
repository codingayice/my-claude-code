package cn.ayice.veyra.control.dto.run;

/**
 * 运行进入后台执行队列后的响应。
 */
public record CreateRunResponse(String runId, boolean accepted) {
}
