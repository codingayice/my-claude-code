package cn.ayice.veyra.runtime;

import cn.ayice.veyra.session.event.SessionEventStream;
import cn.ayice.veyra.session.PendingApprovalState;
import cn.ayice.veyra.runtime.session.SessionRuntime;
import cn.ayice.veyra.runtime.session.RuntimeSessionRegistry;
import cn.ayice.veyra.session.SessionService;
import cn.ayice.veyra.session.SessionState;
import cn.ayice.veyra.session.SessionSummary;
import cn.ayice.veyra.session.TranscriptItem;
import cn.ayice.veyra.session.event.AgentEvent;
import cn.ayice.veyra.runtime.RunCommand;
import cn.ayice.veyra.runtime.RunCoordinator;
import cn.ayice.veyra.runtime.RunMode;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HTTP 控制面访问活动 Veyra 运行时的唯一入口。
 * 创建控制面访问活动运行时的唯一入口。
 */
public class RuntimeHost {

    private final RuntimeSessionRegistry runtimeSessions;
    private final SessionService persistedSessions;
    private final RunCoordinator runs;

    /**
     * 使用会话注册表和统一 Run 协调器创建运行时入口。
     */
    public RuntimeHost(RuntimeSessionRegistry runtimeSessions, SessionService persistedSessions, RunCoordinator runs) {
        this.runtimeSessions = runtimeSessions;
        this.persistedSessions = persistedSessions;
        this.runs = runs;
    }

    /**
     * 创建一个空历史会话并返回当前设置。
     */
    public SessionState createSession() {
        return runtimeSessions.createSession().state();
    }

    /**
     * 返回指定会话的当前设置；会话尚未激活时从 Journal 恢复。
     */
    public SessionState session(String sessionId) {
        return runtimeSessions.getOrCreate(sessionId).state();
    }

    /**
     * 更新指定会话的工作目录和权限模式。
     */
    public SessionState updateSettings(String sessionId, String workingDir, String permissionMode, String runMode) {
        return runtimeSessions.getOrCreate(sessionId).updateSettings(workingDir, permissionMode, runMode);
    }

    /** 使用 expectedRevision 更新会话设置。 */
    public SessionState updateSettings(
            String sessionId, String workingDir, String permissionMode, String runMode, Long expectedRevision
    ) {
        return runtimeSessions.getOrCreate(sessionId)
                .updateSettings(workingDir, permissionMode, runMode, expectedRevision);
    }

    /**
     * 返回持久化会话摘要列表。
     */
    public List<SessionSummary> listSessions() {
        return persistedSessions.list().stream()
                .map(record -> new SessionSummary(
                        record.sessionId(),
                        record.title(),
                        record.createdAt(),
                        record.updatedAt(),
                        record.journalPath()
                ))
                .toList();
    }

    /** 删除空闲会话的运行时状态和持久化 Journal。 */
    public boolean deleteSession(String sessionId) {
        return runtimeSessions.deleteSession(sessionId);
    }

    /**
     * 返回指定会话的持久化转录条目。
     */
    public List<TranscriptItem> transcriptEntries(String sessionId) {
        // 历史读取先触发惰性恢复，避免把悬挂 Run/Tool 原始状态直接暴露给控制面。
        runtimeSessions.getOrCreate(sessionId);
        return persistedSessions.transcript(sessionId);
    }

    /**
     * 校验用户输入并将一次 Run 提交到对应会话的串行队列。
     * <p>方法只负责受理，实际 Agent/Chat 执行在线程池中异步进行。</p>
     */
    public RunSubmission submitRun(String sessionId, String input, String mode) {
        return submitRun(sessionId, input, mode, null);
    }

    /** 从当前位置或一个历史终态 Run 创建新 Run。 */
    public RunSubmission submitRun(String sessionId, String input, String mode, String parentRunId) {
        SessionRuntime session = runtimeSessions.getOrCreate(sessionId);
        String nextInput = input == null ? "" : input.trim();
        if (nextInput.isEmpty()) {
            return RunSubmission.rejected();
        }

        // 先生成稳定 runId 再入队，使 HTTP 202 响应和后续 SSE 事件可以关联同一次运行。
        String runId = UUID.randomUUID().toString();
        RunCommand command = new RunCommand(runId, sessionId, nextInput, RunMode.from(mode));
        SessionRuntime target = session;
        if (parentRunId != null && !parentRunId.isBlank()
                && !parentRunId.equals(session.state().currentRunId())) {
            target = runtimeSessions.runtimeFromCheckpoint(sessionId, parentRunId);
        }
        if (!target.acceptRun(runId, nextInput, command.mode().name().toLowerCase(), parentRunId)) {
            if (target != session) {
                target.close();
            }
            return RunSubmission.rejected();
        }
        if (target != session) {
            runtimeSessions.replace(sessionId, session, target);
            session = target;
        }
        SessionRuntime acceptedSession = session;
        try {
            acceptedSession.enqueue(() -> runs.execute(acceptedSession, command));
        } catch (RuntimeException enqueueFailure) {
            acceptedSession.failEnqueue();
            throw enqueueFailure;
        }
        return RunSubmission.accepted(runId);
    }

    /** 返回当前 Session 全部终态 Run 检查点。 */
    public List<cn.ayice.veyra.session.RunCheckpoint> checkpoints(String sessionId) {
        runtimeSessions.getOrCreate(sessionId);
        return persistedSessions.checkpoints(sessionId);
    }

    /** 回退并持久化当前 Run 指针。 */
    public SessionState restoreCheckpoint(String sessionId, String runId, long expectedRevision) {
        return runtimeSessions.restoreCheckpoint(sessionId, runId, expectedRevision).state();
    }

    /**
     * 将运行期间的新输入默认排到当前 Run 之后；若之后被切换为引导，排队任务会自动跳过。
     */
    public PendingInputSubmission submitFollowup(String sessionId, String input, String mode) {
        SessionRuntime session = runtimeSessions.getOrCreate(sessionId);
        String nextInput = input == null ? "" : input.trim();
        if (nextInput.isEmpty()) {
            return PendingInputSubmission.rejected();
        }

        PendingInputQueue.Message pending = session.addFollowup(nextInput, RunMode.from(mode));
        session.emitStable("input.queued", java.util.Map.of(
                "messageId", pending.id(),
                "text", pending.text(),
                "mode", "followup",
                "steerable", pending.mode() == RunMode.AGENT
        ));
        session.enqueue(() -> {
            PendingInputQueue.Message claimed = session.takePendingInputForNextRun(pending.id());
            if (claimed == null) {
                return;
            }
            String runId = UUID.randomUUID().toString();
            RunCommand command = new RunCommand(runId, sessionId, claimed.text(), claimed.mode());
            if (!session.acceptRun(runId, claimed.text(), claimed.mode().name().toLowerCase())) {
                session.emitStable("input.failed", java.util.Map.of(
                        "messageId", claimed.id(),
                        "reason", "session_still_running"
                ));
                return;
            }
            session.emitStable("input.applied", java.util.Map.of(
                    "messageId", claimed.id(),
                    "mode", "followup"
            ));
            runs.execute(session, command);
        });
        return PendingInputSubmission.accepted(pending);
    }

    /** 将一条尚未消费的 Agent Follow-up 切换为当前 Run 的引导输入。 */
    public boolean steerFollowup(String sessionId, String messageId) {
        SessionRuntime session = runtimeSessions.getOrCreate(sessionId);
        boolean moved = session.steerPendingInput(messageId);
        if (moved) {
            session.emitStable("input.mode.changed", java.util.Map.of(
                    "messageId", messageId,
                    "mode", "steer"
            ));
        }
        return moved;
    }

    /** 取消一条尚未被消费的追随或引导输入。 */
    public boolean cancelFollowup(String sessionId, String messageId) {
        SessionRuntime session = runtimeSessions.getOrCreate(sessionId);
        boolean cancelled = session.cancelPendingInput(messageId);
        if (cancelled) {
            session.emitStable("input.cancelled", java.util.Map.of("messageId", messageId));
        }
        return cancelled;
    }

    /**
     * 返回指定会话尚未处理的工具审批请求。
     */
    public List<PendingApprovalState> pendingApprovals(String sessionId) {
        return runtimeSessions.getOrCreate(sessionId).pendingApprovals();
    }

    /**
     * 将用户审批决定提交给指定会话中等待的工具调用。
     */
    public boolean resolveApproval(String sessionId, String approvalId, String decision) {
        return runtimeSessions.getOrCreate(sessionId).resolveApproval(approvalId, decision);
    }

    /**
     * 查询指定会话可用的斜杠命令补全选项。
     */
    public List<CommandOption> commandOptions(String sessionId, String query) {
        return runtimeSessions.getOrCreate(sessionId).commandOptions(query).stream()
                .map(option -> new CommandOption(
                        option.id(),
                        option.name(),
                        option.description(),
                        option.command()
                ))
                .toList();
    }

    /**
     * 解析并执行一个斜杠命令，无法识别时返回空值。
     */
    public Optional<CommandResult> findCommand(String sessionId, String command) {
        return runtimeSessions.getOrCreate(sessionId).findCommand(command)
                .map(result -> new CommandResult(result.reason(), result.content()));
    }

    /**
     * 返回指定会话的 SSE 事件流。
     */
    public SessionEventStream events(String sessionId) {
        return runtimeSessions.getOrCreate(sessionId).events();
    }
}
