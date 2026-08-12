package cn.ayice.veyra.session;

import cn.ayice.veyra.session.state.AgentState;
import java.util.Map;

/**
 * Read-only control-plane view of an active session.
 */
public record SessionState(
        String sessionId,
        String workingDir,
        String permissionMode,
        String runMode,
        String lastRunStatus,
        long revision,
        String currentRunId,
        String activeRunId,
        Map<String, RunNodeState> runs,
        AgentState agent
) {
    public SessionState {
        runs = runs == null ? Map.of() : Map.copyOf(runs);
        agent = agent == null ? AgentState.empty() : agent;
    }

    public SessionState(String sessionId, String workingDir, String permissionMode) {
        this(sessionId, workingDir, permissionMode, "chat", "idle", 0L, null, null, Map.of(), AgentState.empty());
    }
}
