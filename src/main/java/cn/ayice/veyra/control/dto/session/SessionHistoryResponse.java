package cn.ayice.veyra.control.dto.session;

import java.util.List;

/**
 * 指定 Session 的稳定事件历史。
 */
public record SessionHistoryResponse(List<StableEventResponse> items) {
}
