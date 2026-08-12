package cn.ayice.veyra.session.state;

/** Agent 单次 Run 的显式、可校验执行阶段。 */
public enum AgentPhase {
    READY_FOR_MODEL, CALLING_MODEL, MODEL_RESULT_RECORDED, WAITING_APPROVAL,
    EXECUTING_TOOLS, TOOL_BATCH_COMPLETED, TERMINAL_COMPLETED, TERMINAL_MAX_ROUNDS,
    TERMINAL_FAILED, TERMINAL_CANCELLED, TERMINAL_INTERRUPTED;

    /** 返回该阶段是否禁止继续执行。 */
    public boolean terminal() {
        return name().startsWith("TERMINAL_");
    }
}
