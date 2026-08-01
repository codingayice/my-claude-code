package cn.ayice.veyra.runtime;

/**
 * Immediate result returned after a run is accepted or rejected before execution.
 */
public record RunSubmission(String runId, boolean accepted) {

    /**
     * 创建未受理 Run 或工具授权结果。
     */
    public static RunSubmission rejected() {
        return new RunSubmission("", false);
    }

    /**
     * 创建包含 runId 的已受理 Run 结果。
     */
    public static RunSubmission accepted(String runId) {
        return new RunSubmission(runId, true);
    }
}
