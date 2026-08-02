package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.session.PendingApprovalState;
import cn.ayice.veyra.session.SessionState;
import cn.ayice.veyra.session.event.SessionEventStream;
import cn.ayice.veyra.interaction.command.SlashCommandDispatcher;
import cn.ayice.veyra.interaction.command.SlashCommandOption;
import cn.ayice.veyra.interaction.command.SlashCommandResult;
import cn.ayice.veyra.runtime.RunTarget;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.runtime.agent.AgentLoop;
import cn.ayice.veyra.runtime.chat.ChatLoop;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 单个会话全部可变运行状态的唯一所有者。
 * <p>它持有 Agent/Chat 循环、权限、审批、命令和事件流，并通过私有队列保证同会话 Run 串行。</p>
 */
public class SessionRuntime implements RunTarget, AutoCloseable {

    private final String sessionId;
    private final SessionEventStream events;
    private final ToolApprovalQueue confirmation;
    private final AgentLoop agentLoop;
    private final ChatLoop chatLoop;
    private final PermissionContextStore permissionContextStore;
    private final SlashCommandDispatcher slashCommands;
    private final SessionRunQueue runQueue;

    /**
     * 使用已装配的会话级组件和共享 Run 执行器创建运行时。
     */
    public SessionRuntime(
            String sessionId,
            SessionEventStream events,
            ToolApprovalQueue confirmation,
            AgentLoop agentLoop,
            ChatLoop chatLoop,
            PermissionContextStore permissionContextStore,
            SlashCommandDispatcher slashCommands,
            Executor executor
    ) {
        this.sessionId = sessionId;
        this.events = events;
        this.confirmation = confirmation;
        this.agentLoop = agentLoop;
        this.chatLoop = chatLoop;
        this.permissionContextStore = permissionContextStore;
        this.slashCommands = slashCommands;
        this.runQueue = new SessionRunQueue(executor);
    }

    /**
     * 返回当前会话的稳定标识。
     */
    public String sessionId() {
        return sessionId;
    }

    /**
     * 返回当前会话独占的事件流。
     */
    public SessionEventStream events() {
        return events;
    }

    /**
     * 将 Run 加入当前会话的串行执行队列。
     */
    public CompletableFuture<Void> enqueue(Runnable run) {
        return runQueue.submit(run);
    }

    /**
     * 从当前权限上下文生成可返回给控制面的会话设置快照。
     */
    public SessionState state() {
        PermissionContext context = permissionContextStore.current();
        Path workingDir = context == null ? null : context.workingDir();
        PermissionMode mode = context == null || context.mode() == null
                ? PermissionMode.ASK_EVERY_TIME
                : context.mode();
        return new SessionState(
                sessionId,
                workingDir == null ? "" : workingDir.toString(),
                mode.configValue()
        );
    }

    /**
     * 原子更新当前会话的工作目录和权限模式并返回新快照。
     */
    public SessionState updateSettings(String workingDir, String permissionMode) {
        PermissionMode mode = PermissionMode.fromString(permissionMode);
        Path nextWorkingDir = workingDir == null || workingDir.isBlank()
                ? null
                : Path.of(workingDir);
        // 通过 store 的原子更新入口替换不可变 PermissionContext，避免并发读取到半更新状态。
        permissionContextStore.update(current -> {
            PermissionContext next = current == null
                    ? PermissionContext.builder().build()
                    : current;
            if (nextWorkingDir != null) {
                next = next.withWorkingDirectory(nextWorkingDir);
            }
            return next.withMode(mode);
        });
        return state();
    }

    /**
     * 返回等待当前用户处理的工具审批快照。
     */
    public List<PendingApprovalState> pendingApprovals() {
        return confirmation.pendingApprovals().stream()
                .map(item -> new PendingApprovalState(
                        item.approvalId(),
                        item.tool(),
                        item.arguments(),
                        item.reason()
                ))
                .toList();
    }

    /**
     * 解析并提交一项工具审批决定。
     */
    public boolean resolveApproval(String approvalId, String decision) {
        return confirmation.resolveApproval(approvalId, decision);
    }

    /**
     * 返回与查询文本匹配的斜杠命令选项。
     */
    public List<SlashCommandOption> commandOptions(String query) {
        return slashCommands.suggest(query);
    }

    /**
     * 分发斜杠命令并返回执行结果。
     */
    public Optional<SlashCommandResult> findCommand(String command) {
        return slashCommands.dispatch(command);
    }

    /**
     * 返回主 Agent 当前历史的防御性副本。
     */
    public List<dev.langchain4j.data.message.ChatMessage> agentHistory() {
        return agentLoop.getHistory();
    }

    /**
     * 返回 Chat 模式当前历史的防御性副本。
     */
    public List<dev.langchain4j.data.message.ChatMessage> chatHistory() {
        return chatLoop.getHistory();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void bindRun(String runId) {
        events.bindRun(runId);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void emit(String type, Map<String, Object> payload) {
        events.emit(type, payload);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void executeAgent(String input) {
        agentLoop.process(input);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void executeChat(String input) {
        chatLoop.process(input);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        agentLoop.shutdown();
    }
}
