package cn.ayice.veyra.tooling;


import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * 工具调用需要用户确认时使用的回调接口。当权限系统返回 ASK 时，AgentLoop 或 SubagentRuntime 会调用它。
 */
public abstract class ToolExecutionConfirmation {

    /**
     * 创建需要用户确认的权限决定。
     */
    public Choice ask(ToolExecutionRequest req) {
        return ask(req, null);
    }

    /**
     * 创建需要用户确认的权限决定。
     */
    public abstract Choice ask(ToolExecutionRequest req, String reason);

    /**
     * Choice 枚举对应流程允许的离散状态。
     */
    public enum Choice {
        ALLOW_ONCE,
        ALLOW_FOR_SESSION,
        DENY
    }
}
