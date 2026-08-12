package cn.ayice.veyra.session.persistence;

/** Run 树中一个不可变父子节点的持久化索引条目。 */
public record RunIndexEntry(
        String runId,
        String parentRunId,
        long startedRevision,
        Long terminalRevision,
        String status,
        boolean snapshotAvailable
) {
    public RunIndexEntry {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
        if (startedRevision <= 0) {
            throw new IllegalArgumentException("startedRevision must be positive");
        }
        status = status == null || status.isBlank() ? "running" : status;
    }

    /** 当前 Run 是否已写入唯一终态。 */
    public boolean terminal() {
        return terminalRevision != null;
    }
}
