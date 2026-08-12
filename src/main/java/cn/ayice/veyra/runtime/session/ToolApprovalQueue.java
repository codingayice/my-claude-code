package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.session.event.AgentEventSink;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import cn.ayice.veyra.session.persistence.SessionJournalRecorder;

/**
 * Owns pending user approvals for one active session and publishes their lifecycle events.
 */
public class ToolApprovalQueue extends ToolExecutionConfirmation {

    private static final Logger log = LoggerFactory.getLogger(ToolApprovalQueue.class);

    private final AgentEventSink events;
    private final SessionJournalRecorder journal;
    private final ConcurrentHashMap<String, CompletableFuture<Choice>> pending = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PendingApproval> approvals = new ConcurrentHashMap<>();

    public ToolApprovalQueue(AgentEventSink events) {
        this(events, null);
    }

    /** 使用独立实时通知与稳定事实写入边界创建审批队列。 */
    public ToolApprovalQueue(AgentEventSink events, SessionJournalRecorder journal) {
        this.events = events;
        this.journal = journal;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Choice ask(ToolExecutionRequest request) {
        return ask(request, null);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Choice ask(ToolExecutionRequest request, String reason) {
        String approvalId = UUID.randomUUID().toString();
        PendingApproval approval = new PendingApproval(
                approvalId,
                request.name(),
                request.arguments(),
                reason == null ? "" : reason
        );
        approvals.put(approvalId, approval);
        CompletableFuture<Choice> future = new CompletableFuture<>();
        pending.put(approvalId, future);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approvalId", approvalId);
        payload.put("toolUseId", request.id());
        payload.put("tool", request.name());
        payload.put("arguments", request.arguments() == null ? "" : request.arguments());
        payload.put("reason", reason == null ? "" : reason);
        if (journal != null) {
            journal.recordDomainEvent(cn.ayice.veyra.session.persistence.SessionJournalTypes.PERMISSION_REQUESTED, payload);
        }
        events.emit("permission.requested", payload);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Choice.DENY;
        } catch (Exception e) {
            log.error("tool approval wait failed approvalId={} tool={}", approvalId, request.name(), e);
            return Choice.DENY;
        } finally {
            pending.remove(approvalId);
            approvals.remove(approvalId);
        }
    }

    /**
     * 解析并校验审批。
     */
    public boolean resolveApproval(String approvalId, String decision) {
        CompletableFuture<Choice> future = pending.get(approvalId);
        if (future == null) {
            return false;
        }
        Choice choice = switch (decision == null ? "" : decision.toLowerCase()) {
            case "allow_for_session" -> Choice.ALLOW_FOR_SESSION;
            case "allow_once", "allow" -> Choice.ALLOW_ONCE;
            default -> Choice.DENY;
        };
        Map<String, Object> payload = Map.of(
                "approvalId", approvalId,
                "decision", decision
        );
        if (journal != null) {
            journal.recordDomainEvent(cn.ayice.veyra.session.persistence.SessionJournalTypes.PERMISSION_RESOLVED, payload);
        }
        events.emit("permission.resolved", payload);
        future.complete(choice);
        return true;
    }

    /**
     * 返回当前会话尚未处理的工具审批快照。
     */
    public List<PendingApproval> pendingApprovals() {
        return new ArrayList<>(approvals.values());
    }

    /**
     * 等待用户决定的工具调用及其完成信号。
     */
    public record PendingApproval(
            String approvalId,
            String tool,
            String arguments,
            String reason
    ) {
    }
}
