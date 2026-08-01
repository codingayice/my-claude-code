package cn.ayice.veyra.tooling;

import cn.ayice.veyra.tooling.permission.PermissionChecker;
import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionDecision;
import cn.ayice.veyra.tooling.BaseTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Explicit differences between main-agent and subagent tool execution.
 */
public interface ToolExecutionPolicy {

    /**
     * 根据工具、参数和当前权限上下文计算允许、询问或拒绝决定。
     */
    PermissionDecision decide(BaseTool tool, ToolExecutionRequest request, PermissionContext context);

    /**
     * 返回当前执行表面是否支持暂停并请求用户审批。
     */
    boolean canAskPermission();

    /**
     * 返回审批事件是否应包含权限策略给出的原因。
     */
    default boolean includeDecisionReasonInApproval() {
        return true;
    }

    /**
     * 将拒绝决定转换为可写入审批结果的稳定原因。
     */
    default String deniedApprovalReason(PermissionDecision decision) {
        return decision.reason();
    }

    /**
     * 返回工具成功但无输出时写入模型上下文的占位内容。
     */
    String emptySuccessContent();

    /**
     * 返回空白工具输出是否按无输出结果处理。
     */
    default boolean treatBlankContentAsEmpty() {
        return true;
    }

    /**
     * 返回主 Agent 使用的工具执行策略。
     */
    static ToolExecutionPolicy mainAgent() {
        return new ToolExecutionPolicy() {
            /**
             * {@inheritDoc}
             */
            @Override
            public PermissionDecision decide(
                    BaseTool tool,
                    ToolExecutionRequest request,
                    PermissionContext context
            ) {
                return PermissionChecker.decide(tool, request, context);
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean canAskPermission() {
                return true;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean includeDecisionReasonInApproval() {
                return false;
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public String emptySuccessContent() {
                return "<success>工具已成功执行，但结果为空</success>";
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public boolean treatBlankContentAsEmpty() {
                return false;
            }
        };
    }
}
