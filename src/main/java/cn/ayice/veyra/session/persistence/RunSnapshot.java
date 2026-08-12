package cn.ayice.veyra.session.persistence;

import cn.ayice.veyra.session.state.AgentState;

/** 一个终态 Run 的不可变、可由事件重建的 Agent 状态基线。 */
public record RunSnapshot(
        int schemaVersion,
        String sessionId,
        String runId,
        String parentRunId,
        long terminalRevision,
        String terminalEventId,
        AgentState agentState,
        String checksum
) {
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public RunSnapshot {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported RunSnapshot schemaVersion: " + schemaVersion);
        }
        if (sessionId == null || sessionId.isBlank() || runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("snapshot identifiers must not be blank");
        }
        if (terminalRevision <= 0) {
            throw new IllegalArgumentException("terminalRevision must be positive");
        }
        if (terminalEventId == null || terminalEventId.isBlank() || agentState == null) {
            throw new IllegalArgumentException("snapshot terminal event and agentState are required");
        }
    }
}
