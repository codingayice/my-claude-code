package cn.ayice.veyra.session;

/** API 与领域层共用的不可变 Run 树节点。 */
public record RunNodeState(
        String runId,
        String parentRunId,
        long startedRevision,
        Long terminalRevision,
        String status,
        boolean snapshotAvailable
) {
}
