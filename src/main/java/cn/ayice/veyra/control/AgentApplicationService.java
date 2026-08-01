package cn.ayice.veyra.control;

import cn.ayice.veyra.control.dto.approval.ApprovalListResponse;
import cn.ayice.veyra.control.dto.approval.PendingApprovalResponse;
import cn.ayice.veyra.control.dto.command.ExecuteSlashCommandResponse;
import cn.ayice.veyra.control.dto.command.SlashCommandListResponse;
import cn.ayice.veyra.control.dto.command.SlashCommandOptionResponse;
import cn.ayice.veyra.control.dto.run.CreateRunResponse;
import cn.ayice.veyra.control.dto.session.SessionListResponse;
import cn.ayice.veyra.control.dto.session.SessionRecordResponse;
import cn.ayice.veyra.control.dto.session.SessionResponse;
import cn.ayice.veyra.control.dto.session.TranscriptEntryResponse;
import cn.ayice.veyra.control.dto.session.TranscriptResponse;
import cn.ayice.veyra.control.dto.session.UpdateSessionSettingsRequest;
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
        return toSessionResponse(runtimeHost.updateSettings(
                sessionId,
                request.workingDir(),
                request.permissionMode()
        ));
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

    /**
     * 提交一次 Agent 或 Chat Run，并返回稳定运行标识。
     */
    public CreateRunResponse createRun(String sessionId, String input, String mode) {
        RunSubmission submission = runtimeHost.submitRun(sessionId, input, mode);
        return new CreateRunResponse(submission.runId(), submission.accepted());
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

    /**
     * 返回当前会话尚未处理的工具审批快照。
     */
    public ApprovalListResponse pendingApprovals(String sessionId) {
        var items = runtimeHost.pendingApprovals(sessionId).stream()
                .map(approval -> new PendingApprovalResponse(
                        approval.approvalId(),
                        approval.tool(),
                        approval.arguments(),
                        approval.reason()
                ))
                .toList();
        return new ApprovalListResponse(items);
    }

    /**
     * 将用户审批决定提交到指定会话。
     */
    public boolean decideApproval(String sessionId, String approvalId, String decision) {
        return runtimeHost.resolveApproval(sessionId, approvalId, decision);
    }

    /**
     * 把运行时会话状态转换为控制面响应。
     */
    private SessionResponse toSessionResponse(SessionState session) {
        return new SessionResponse(session.sessionId(), session.workingDir(), session.permissionMode());
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
                record.transcriptPath().toString()
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
