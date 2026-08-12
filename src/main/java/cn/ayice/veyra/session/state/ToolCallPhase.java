package cn.ayice.veyra.session.state;

/** ToolUse 的单调生命周期阶段。 */
public enum ToolCallPhase { DECLARED, WAITING_APPROVAL, AUTHORIZED, REJECTED, EXECUTION_STARTED, RESULT_RECORDED }
