package cn.ayice.veyra.control.dto.session;

/** 使用乐观并发恢复到指定终态 Run。 */
public record RestoreCheckpointRequest(String runId, long expectedRevision) {
}
