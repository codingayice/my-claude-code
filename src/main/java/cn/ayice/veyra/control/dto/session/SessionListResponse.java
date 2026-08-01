package cn.ayice.veyra.control.dto.session;

import java.util.List;

/**
 * 会话列表响应。
 */
public record SessionListResponse(List<SessionRecordResponse> items) {
}
