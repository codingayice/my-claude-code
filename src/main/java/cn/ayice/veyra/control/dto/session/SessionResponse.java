package cn.ayice.veyra.control.dto.session;

import cn.ayice.veyra.session.RunNodeState;
import cn.ayice.veyra.session.state.AgentState;
import java.util.Map;

/**
 * 会话详情和会话设置的基础响应。
 */
public record SessionResponse(
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
}
