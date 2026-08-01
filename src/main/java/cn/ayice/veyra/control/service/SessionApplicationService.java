package cn.ayice.veyra.control.service;

import cn.ayice.veyra.host.RuntimeHost;
import cn.ayice.veyra.host.SessionState;
import cn.ayice.veyra.host.SessionSummary;
import cn.ayice.veyra.host.TranscriptItem;
import cn.ayice.veyra.control.dto.session.SessionListResponse;
import cn.ayice.veyra.control.dto.session.SessionRecordResponse;
import cn.ayice.veyra.control.dto.session.SessionResponse;
import cn.ayice.veyra.control.dto.session.TranscriptEntryResponse;
import cn.ayice.veyra.control.dto.session.TranscriptResponse;
import cn.ayice.veyra.control.dto.session.UpdateSessionSettingsRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话应用服务。它只处理会话生命周期、设置和 transcript 查询。
 */
@Service
public class SessionApplicationService {

    private final RuntimeHost runtimeHost;

    /**
     * 注入该服务运行所需依赖并创建 SessionApplicationService。
     */
    public SessionApplicationService(RuntimeHost runtimeHost) {
        this.runtimeHost = runtimeHost;
    }

    /**
     * 创建session
     */
    public SessionResponse createSession() {
        return toSessionResponse(runtimeHost.createSession());
    }

    /**
     * 返回持久化会话摘要列表，并保持最近更新的会话优先。
     */
    public SessionListResponse listSessions() {
        List<SessionRecordResponse> items = runtimeHost.listSessions().stream()
                .map(this::toSessionRecordResponse)
                .toList();
        return new SessionListResponse(items);
    }

    /**
     * 返回指定会话的当前状态或 API 表示。
     */
    public SessionResponse session(String sessionId) {
        return toSessionResponse(runtimeHost.session(sessionId));
    }

    /**
     * 校验并更新会话权限设置，返回更新后的会话状态。
     */
    public SessionResponse updateSettings(String sessionId, UpdateSessionSettingsRequest request) {
        return toSessionResponse(runtimeHost.updateSettings(sessionId, request.workingDir(), request.permissionMode()));
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
     * 把运行时会话状态映射为不泄露内部对象的接口响应。
     */
    private SessionResponse toSessionResponse(SessionState session) {
        return new SessionResponse(
                session.sessionId(),
                session.workingDir(),
                session.permissionMode()
        );
    }

    /**
     * 把持久化会话摘要映射为列表接口条目。
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
     * 把 transcript 存储模型映射为接口传输对象。
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
}
