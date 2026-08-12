package cn.ayice.veyra.session;

/** 用户可选择的终态 Run 检查点。 */
public record RunCheckpoint(
        String runId,
        String parentRunId,
        long terminalRevision,
        String status,
        boolean current,
        boolean snapshotAvailable
) {
}
