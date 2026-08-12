package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionUpdateSuggestions;
import cn.ayice.veyra.tool.permission.PermissionContextStore.Update;
import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 工具授权和执行生命周期的统一实现。
 * <p>所有主 Agent 与子 Agent 工具策略都通过该类完成决策、审批、执行和空结果规范化。</p>
 */
public class ToolService {

    private static final Logger log = LoggerFactory.getLogger(ToolService.class);

    private final ToolCatalog catalog;
    private final PermissionContextStore permissionContextStore;

    /**
     * 使用工具分发器、人工审批入口和会话权限存储创建工具引擎。
     */
    public ToolService(
            ToolCatalog catalog,
            PermissionContextStore permissionContextStore
    ) {
        this.catalog = catalog;
        this.permissionContextStore = permissionContextStore;
    }

    /**
     * 根据执行策略完成工具权限决策，并在 ASK 场景同步等待用户审批。
     */
    public Authorization authorize(
            ToolExecutionRequest request,
            PermissionContext context,
            ToolExecutionPolicy policy,
            ToolExecutionObserver observer
    ) {
        // 策略先给出 ALLOW/ASK/DENY，工具执行与权限判断严格分离。
        BaseTool tool = catalog == null ? null : catalog.find(request.name());
        PermissionDecision decision = policy.decide(tool, request, context);
        observer.authorizationDecided(request, decision);
        if (decision.kind() == PermissionDecision.Kind.DENY) {
            return rejected(request, tool, decision, context, decision.reason());
        }
        if (decision.kind() != PermissionDecision.Kind.ASK) {
            return allowed(request, tool, decision, context);
        }

        observer.permissionRequested(request, decision);
        if (!policy.canAskPermission()) {
            return rejected(request, tool, decision, context, policy.deniedApprovalReason(decision));
        }
        return new Authorization("approval_required", decision, request, tool, context,
                UUID.randomUUID().toString(), null);
    }

    /** 将持久化审批决定恢复为可执行或拒绝的授权描述。 */
    public Authorization resolve(ToolExecutionRequest request, String decision, PermissionContext context) {
        BaseTool tool = catalog == null ? null : catalog.find(request.name());
        PermissionDecision original = PermissionDecision.ask("persisted approval");
        if ("deny".equals(decision)) {
            return rejected(request, tool, original, context, "用户拒绝了工具调用");
        }
        PermissionContext next = context;
        if ("allow_for_session".equals(decision)) {
            List<Update> updates = PermissionUpdateSuggestions.generateForSessionAllow(request, context);
            next = PermissionContextStore.applyTo(context, updates);
            if (permissionContextStore != null) permissionContextStore.apply(updates);
        }
        return allowed(request, tool, original, next);
    }

    /**
     * 执行已授权工具，并将空内容转换成策略定义的稳定结果文本。
     */
    public Execution execute(
            Authorization authorization,
            PermissionContext context,
            ToolExecutionPolicy policy
    ) {
        if (!authorization.allowed()) {
            throw new IllegalArgumentException("rejected tool request cannot be executed");
        }
        try {
            ToolResult result = catalog.execute(authorization.request(), context);
            String content = result.content();
            // 模型协议要求每个 tool use 都有非空 tool result，因此在生命周期边界统一补齐空结果。
            boolean empty = content == null
                    || (policy.treatBlankContentAsEmpty() ? content.isBlank() : content.isEmpty());
            if (empty) {
                content = result.success()
                        ? policy.emptySuccessContent()
                        : "<error>工具执行失败</error>";
            }
            return new Execution(authorization.request(), result, content);
        } catch (RuntimeException e) {
            log.error(
                    "tool lifecycle failed toolUseId={} tool={}",
                    authorization.request().id(),
                    authorization.request().name(),
                    e
            );
            throw e;
        }
    }

    /**
     * 创建允许执行的工具授权结果。
     */
    private static Authorization allowed(
            ToolExecutionRequest request,
            BaseTool tool,
            PermissionDecision decision,
            PermissionContext context
    ) {
        return new Authorization("allowed", decision, request, tool, context, null, null);
    }

    /**
     * 创建包含稳定拒绝原因的工具授权结果。
     */
    private static Authorization rejected(
            ToolExecutionRequest request,
            BaseTool tool,
            PermissionDecision decision,
            PermissionContext context,
            String reason
    ) {
        return new Authorization("denied", decision, request, tool, context, null, reason);
    }

    /**
     * 单个工具请求经过权限决策和审批后的不可变授权结果。
     */
    public record Authorization(
            String status,
            PermissionDecision decision,
            ToolExecutionRequest request,
            BaseTool tool,
            PermissionContext context,
            String approvalId,
            String rejectionReason
    ) {
        /**
         * 返回本次授权是否允许继续执行。
         */
        public boolean allowed() {
            return "allowed".equals(status);
        }

        /** 返回是否需要先持久化用户审批。 */
        public boolean approvalRequired() { return "approval_required".equals(status); }
    }

    /**
     * 可直接追加到模型历史的规范化工具执行结果。
     */
    public record Execution(ToolExecutionRequest request, ToolResult result, String content) {
    }
}
