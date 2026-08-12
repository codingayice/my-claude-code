package cn.ayice.veyra.control;

import cn.ayice.veyra.control.dto.command.ExecuteSlashCommandResponse;
import cn.ayice.veyra.control.dto.command.SlashCommandListResponse;
import cn.ayice.veyra.control.dto.command.SlashCommandOptionResponse;
import cn.ayice.veyra.control.dto.run.CreateRunResponse;
import cn.ayice.veyra.control.dto.run.CreateFollowupResponse;
import cn.ayice.veyra.runtime.control.RunControlRequest;
import cn.ayice.veyra.runtime.control.RunControlResult;
import cn.ayice.veyra.control.dto.session.SessionListResponse;
import cn.ayice.veyra.control.dto.session.SessionRecordResponse;
import cn.ayice.veyra.control.dto.session.SessionResponse;
import cn.ayice.veyra.control.dto.session.TranscriptEntryResponse;
import cn.ayice.veyra.control.dto.session.TranscriptResponse;
import cn.ayice.veyra.control.dto.session.UpdateSessionSettingsRequest;
import cn.ayice.veyra.control.dto.session.CheckpointListResponse;
import cn.ayice.veyra.control.dto.session.RunCheckpointResponse;
import cn.ayice.veyra.control.exception.AgentApiException;
import cn.ayice.veyra.runtime.CommandOption;
import cn.ayice.veyra.runtime.CommandResult;
import cn.ayice.veyra.runtime.RunSubmission;
import cn.ayice.veyra.runtime.RuntimeHost;
import cn.ayice.veyra.session.SessionState;
import cn.ayice.veyra.session.SessionSummary;
import cn.ayice.veyra.session.TranscriptItem;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent HTTP 控制面的统一应用服务，集中编排会话、运行、命令和审批用例及 DTO 转换。
 */
@Service
public class AgentApplicationService {

    private final RuntimeHost runtimeHost;

    /**
     * 使用唯一运行时入口创建 Agent 控制面应用服务。
     */
    public AgentApplicationService(RuntimeHost runtimeHost) {
        this.runtimeHost = runtimeHost;
    }

    /**
     * 创建空历史会话并返回 HTTP 表示。
     */
    public SessionResponse createSession() {
        return toSessionResponse(runtimeHost.createSession());
    }

    /**
     * 返回持久化会话摘要列表，并保持存储层给出的稳定顺序。
     */
    public SessionListResponse listSessions() {
        List<SessionRecordResponse> items = runtimeHost.listSessions().stream()
                .map(this::toSessionRecordResponse)
                .toList();
        return new SessionListResponse(items);
    }

    /** 删除一个当前未运行的会话。 */
    public boolean deleteSession(String sessionId) {
        return runtimeHost.deleteSession(sessionId);
    }

    /**
     * 返回指定会话的当前状态。
     */
    public SessionResponse session(String sessionId) {
        return toSessionResponse(runtimeHost.session(sessionId));
    }

    /**
     * 更新指定会话设置并返回新状态。
     */
    public SessionResponse updateSettings(String sessionId, UpdateSessionSettingsRequest request) {
        try {
            return toSessionResponse(runtimeHost.updateSettings(
                    sessionId, request.workingDir(), request.permissionMode(), request.runMode(),
                    request.expectedRevision()
            ));
        } catch (IllegalStateException conflict) {
            if ("SESSION_REVISION_CONFLICT".equals(conflict.getMessage())) {
                throw new AgentApiException(HttpStatus.CONFLICT, "A0409", conflict.getMessage(), conflict);
            }
            throw conflict;
        }
    }

    /**
     * 返回指定会话按写入顺序排列的转录记录。
     */
    public TranscriptResponse transcript(String sessionId) {
        List<TranscriptEntryResponse> items = runtimeHost.transcriptEntries(sessionId).stream()
                .map(this::toTranscriptEntryResponse)
                .toList();
        return new TranscriptResponse(items);
    }

    /** 使用可选历史父 Run 提交一次执行。 */
    public CreateRunResponse createRun(String sessionId, String input, String mode, String parentRunId) {
        RunSubmission submission = runtimeHost.submitRun(sessionId, input, mode, parentRunId);
        return new CreateRunResponse(submission.runId(), submission.accepted());
    }

    /** 从当前检查点提交一次 Agent 或 Chat Run。 */
    public CreateRunResponse createRun(String sessionId, String input, String mode) {
        return createRun(sessionId, input, mode, null);
    }

    /** 返回 Session 内全部终态 Run 检查点。 */
    public CheckpointListResponse checkpoints(String sessionId) {
        return new CheckpointListResponse(runtimeHost.checkpoints(sessionId).stream()
                .map(checkpoint -> new RunCheckpointResponse(
                        checkpoint.runId(), checkpoint.parentRunId(), checkpoint.terminalRevision(),
                        checkpoint.status(), checkpoint.current(), checkpoint.snapshotAvailable()
                ))
                .toList());
    }

    /** 将当前 Session 回退到指定检查点。 */
    public SessionResponse restoreCheckpoint(String sessionId, String runId, long expectedRevision) {
        try {
            return toSessionResponse(runtimeHost.restoreCheckpoint(sessionId, runId, expectedRevision));
        } catch (IllegalStateException conflict) {
            if ("SESSION_REVISION_CONFLICT".equals(conflict.getMessage())
                    || "SESSION_ALREADY_RUNNING".equals(conflict.getMessage())) {
                throw new AgentApiException(HttpStatus.CONFLICT, "A0409", conflict.getMessage(), conflict);
            }
            throw conflict;
        }
    }

    /** 将运行期间的新输入加入默认追随队列。 */
    public CreateFollowupResponse createFollowup(String sessionId, String input, String mode) {
        var submission = runtimeHost.submitFollowup(sessionId, input, mode);
        return new CreateFollowupResponse(
                submission.messageId(),
                submission.accepted(),
                submission.steerable()
        );
    }

    /** 将尚未消费的追随输入切换为引导。 */
    public boolean steerFollowup(String sessionId, String messageId) {
        return runtimeHost.steerFollowup(sessionId, messageId);
    }

    /** 取消尚未被消费的追随或引导输入。 */
    public boolean cancelFollowup(String sessionId, String messageId) {
        return runtimeHost.cancelFollowup(sessionId, messageId);
    }

    /**
     * 返回与查询文本匹配的斜杠命令补全选项。
     */
    public SlashCommandListResponse commandOptions(String sessionId, String query) {
        List<SlashCommandOptionResponse> items = runtimeHost.commandOptions(sessionId, query).stream()
                .map(this::toCommandOptionResponse)
                .toList();
        return new SlashCommandListResponse(items);
    }

    /**
     * 执行斜杠命令；命令不存在时转换为稳定 HTTP 失败。
     */
    public ExecuteSlashCommandResponse executeCommand(String sessionId, String command) {
        CommandResult result = runtimeHost.findCommand(sessionId, command)
                .orElseThrow(() -> new AgentApiException(HttpStatus.NOT_FOUND, "unknown slash command"));
        return new ExecuteSlashCommandResponse(result.reason(), result.content());
    }


    /** 分发统一 Run 控制并转换稳定错误协议。 */
    public RunControlResult controlRun(String sessionId, String runId, RunControlRequest request) {
        try {
            return runtimeHost.controlRun(sessionId, runId, request);
        } catch (IllegalArgumentException failure) {
            if ("UNSUPPORTED_RUN_CONTROL".equals(failure.getMessage())) {
                throw new AgentApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "UNSUPPORTED_RUN_CONTROL", failure.getMessage(), failure);
            }
            throw failure;
        } catch (IllegalStateException failure) {
            String code = failure.getMessage();
            HttpStatus status = switch (code) {
                case "RUN_NOT_FOUND", "APPROVAL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
                default -> HttpStatus.CONFLICT;
            };
            throw new AgentApiException(status, code, code, failure);
        }
    }

    /**
     * 把运行时会话状态转换为控制面响应。
     */
    private SessionResponse toSessionResponse(SessionState session) {
        return new SessionResponse(
                session.sessionId(),
                session.workingDir(),
                session.permissionMode(),
                session.runMode(),
                session.lastRunStatus(),
                session.revision(),
                session.currentRunId(),
                session.activeRunId(),
                session.runs(),
                session.agent()
        );
    }

    /**
     * 把持久化会话摘要转换为列表响应。
     */
    private SessionRecordResponse toSessionRecordResponse(SessionSummary record) {
        return new SessionRecordResponse(
                record.sessionId(),
                record.title(),
                record.createdAt().toString(),
                record.updatedAt().toString(),
                record.journalPath().toString()
        );
    }

    /**
     * 把持久化转录条目转换为控制面响应。
     */
    private TranscriptEntryResponse toTranscriptEntryResponse(TranscriptItem entry) {
        return new TranscriptEntryResponse(
                entry.id(),
                entry.sessionId(),
                entry.role(),
                entry.content(),
                entry.toolUseId(),
                entry.toolName(),
                entry.timestamp()
        );
    }

    /**
     * 把内部命令选项转换为控制面补全响应。
     */
    private SlashCommandOptionResponse toCommandOptionResponse(CommandOption option) {
        return new SlashCommandOptionResponse(option.id(), option.name(), option.description(), option.command());
    }
}
