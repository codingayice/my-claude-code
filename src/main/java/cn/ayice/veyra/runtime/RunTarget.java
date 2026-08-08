package cn.ayice.veyra.runtime;

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

    /**
     * 正常返回后持久化 Run 终态；无持久化能力的测试目标保持 no-op。
     */
    default void completeRun(Map<String, Object> payload) {
    }

    /**
     * 未处理异常穿出后持久化 Run 失败终态。
     */
    default void failRun(Map<String, Object> payload) {
    }
}
