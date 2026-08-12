package cn.ayice.veyra.control.dto.session;

import java.util.List;

/** 一个 Session 的 Run 检查点列表。 */
public record CheckpointListResponse(List<RunCheckpointResponse> items) {
    public CheckpointListResponse {
        items = List.copyOf(items);
    }
}
