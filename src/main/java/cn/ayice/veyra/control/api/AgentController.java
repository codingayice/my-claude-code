package cn.ayice.veyra.control.api;

import cn.ayice.veyra.control.AgentApplicationService;
import cn.ayice.veyra.control.dto.approval.ApprovalDecisionRequest;
import cn.ayice.veyra.control.dto.approval.ApprovalListResponse;
import cn.ayice.veyra.control.dto.command.ExecuteSlashCommandRequest;
import cn.ayice.veyra.control.dto.command.ExecuteSlashCommandResponse;
import cn.ayice.veyra.control.dto.command.SlashCommandListResponse;
import cn.ayice.veyra.control.dto.common.ApiResponse;
import cn.ayice.veyra.control.dto.run.CreateRunRequest;
import cn.ayice.veyra.control.dto.run.CreateRunResponse;
import cn.ayice.veyra.control.dto.session.SessionListResponse;
import cn.ayice.veyra.control.dto.session.SessionResponse;
import cn.ayice.veyra.control.dto.session.TranscriptResponse;
import cn.ayice.veyra.control.dto.session.UpdateSessionSettingsRequest;
import cn.ayice.veyra.control.exception.AgentApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Agent JSON API 聚合入口。Spring Controller 负责协议边界，具体用例委托给 application service。
 */
@RestController
@RequestMapping("/v1")
public class AgentController {

    private final AgentApplicationService application;

    /**
     * 注入应用服务并创建 AgentController。
     */
    public AgentController(AgentApplicationService application) {
        this.application = application;
    }

    /**
     * 返回后端进程可接收请求的健康状态。
     */
    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.success(Map.of("ok", true));
    }

    /**
     * 创建session
     * @return
     */
    @PostMapping("/sessions")
    public ApiResponse<SessionResponse> createSession() {
        return ApiResponse.success(application.createSession());
    }

    /**
     * 返回持久化会话摘要列表，并保持最近更新的会话优先。
     */
    @GetMapping("/sessions")
    public ApiResponse<SessionListResponse> listSessions() {
        return ApiResponse.success(application.listSessions());
    }

    /**
     * 返回指定会话的当前状态或 API 表示。
     */
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<SessionResponse> session(@PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(application.session(sessionId));
    }

    /**
     * 校验并更新会话权限设置，返回更新后的会话状态。
     */
    @PatchMapping("/sessions/{sessionId}/settings")
    public ApiResponse<SessionResponse> updateSettings(
            @PathVariable("sessionId") String sessionId,
            @RequestBody UpdateSessionSettingsRequest request
    ) {
        return ApiResponse.success(application.updateSettings(sessionId, request));
    }

    /**
     * 返回指定会话按写入顺序排列的转录记录。
     */
    @GetMapping("/sessions/{sessionId}/transcript")
    public ApiResponse<TranscriptResponse> transcript(@PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(application.transcript(sessionId));
    }

    /**
     * 根据输入创建对应对象。
     */
    @PostMapping("/sessions/{sessionId}/runs")
    public ResponseEntity<ApiResponse<CreateRunResponse>> createRun(
            @PathVariable("sessionId") String sessionId,
            @RequestBody CreateRunRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(application.createRun(sessionId, request.input(), request.mode())));
    }

    /**
     * 返回与查询文本匹配的斜杠命令补全选项。
     */
    @GetMapping("/sessions/{sessionId}/slash-commands")
    public ApiResponse<SlashCommandListResponse> slashCommandOptions(
            @PathVariable("sessionId") String sessionId,
            @RequestParam(name = "query", defaultValue = "") String query
    ) {
        return ApiResponse.success(application.commandOptions(sessionId, query));
    }

    /**
     * 执行斜杠命令命令用例。
     */
    @PostMapping("/sessions/{sessionId}/slash-command-executions")
    public ApiResponse<ExecuteSlashCommandResponse> executeSlashCommand(
            @PathVariable("sessionId") String sessionId,
            @RequestBody ExecuteSlashCommandRequest request
    ) {
        return ApiResponse.success(application.executeCommand(sessionId, request.command()));
    }

    /**
     * 返回当前会话尚未处理的工具审批快照。
     */
    @GetMapping("/sessions/{sessionId}/approvals")
    public ApiResponse<ApprovalListResponse> pendingApprovals(@PathVariable("sessionId") String sessionId) {
        return ApiResponse.success(application.pendingApprovals(sessionId));
    }

    /**
     * 校验审批选项并完成指定待审批工具调用，返回最新审批状态。
     */
    @PostMapping("/sessions/{sessionId}/approvals/{approvalId}/decision")
    public ApiResponse<Map<String, Object>> decideApproval(
            @PathVariable("sessionId") String sessionId,
            @PathVariable("approvalId") String approvalId,
            @RequestBody ApprovalDecisionRequest request
    ) {
        if (!application.decideApproval(sessionId, approvalId, request.decision())) {
            throw new AgentApiException(HttpStatus.NOT_FOUND, "approval not found");
        }
        return ApiResponse.success(Map.of("ok", true));
    }
}
