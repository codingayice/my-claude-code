package cn.ayice.veyra.control.dto.session;

import java.util.List;

/**
 * 会话 transcript 详情响应。
 */
public record TranscriptResponse(List<TranscriptEntryResponse> items) {
}
