package cn.ayice.veyra.control.dto.session;

/** 桌面端检查点时间线使用的稳定 DTO。 */
public record RunCheckpointResponse(
        String runId,
        String parentRunId,
        long terminalRevision,
        String status,
        boolean current,
        boolean restorable
) {
}
