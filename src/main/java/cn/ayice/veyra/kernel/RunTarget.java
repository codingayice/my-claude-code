package cn.ayice.veyra.kernel;

import java.util.Map;

/**
 * Runtime-owned execution target used by the kernel without depending on host internals.
 */
public interface RunTarget {

    /**
     * 将后续会话事件关联到当前 runId。
     */
    void bindRun(String runId);

    /**
     * 处理并传播 {@code emit} 对应的事件。
     */
    void emit(String type, Map<String, Object> payload);

    /**
     * 把输入交给当前会话的主 Agent 循环执行。
     */
    void executeAgent(String input);

    /**
     * 把输入交给当前会话的无工具 Chat 循环执行。
     */
    void executeChat(String input);
}
