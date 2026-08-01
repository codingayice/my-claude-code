package cn.ayice.veyra.runtime.agent;

import cn.ayice.veyra.context.ContextBudgetService;
import cn.ayice.veyra.context.WorkingMessage;
import cn.ayice.veyra.compaction.StableHistorySnapshot;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 单次 process 内的 Agent 循环状态，也是该阶段 Working History 的唯一可变来源。
 */
public final class LoopState {

    public static final String ACTIVE = "ACTIVE";
    public static final String TERMINAL_COMPLETED = "TERMINAL_COMPLETED";
    public static final String TERMINAL_MAX_ROUNDS = "TERMINAL_MAX_ROUNDS";
    public static final String TERMINAL_FAILED = "TERMINAL_FAILED";
    public static final String TERMINAL_CANCELLED = "TERMINAL_CANCELLED";

    private List<WorkingMessage> messages;
    private long nextSequence;
    private long currentStableSequence;
    private String state;
    private String transitionReason;
    private int turnCount;
    private int failureCount;
    private ChatRequest request;
    private AiMessage aiMessage;
    private List<ToolExecutionRequest> approvedRequests;
    private ContextBudgetService.CapacityState capacityState;

    private LoopState(
            List<WorkingMessage> messages,
            long nextSequence,
            long currentStableSequence,
            String state,
            String transitionReason,
            int turnCount,
            int failureCount,
            ChatRequest request,
            AiMessage aiMessage,
            List<ToolExecutionRequest> approvedRequests,
            ContextBudgetService.CapacityState capacityState
    ) {
        this.messages = new ArrayList<>(messages);
        this.nextSequence = nextSequence;
        this.currentStableSequence = currentStableSequence;
        this.state = state;
        this.transitionReason = transitionReason;
        this.turnCount = turnCount;
        this.failureCount = failureCount;
        this.request = request;
        this.aiMessage = aiMessage;
        this.approvedRequests = approvedRequests == null ? null : List.copyOf(approvedRequests);
        this.capacityState = capacityState;
    }

    /**
     * 接管 AgentLoop 在 process 之间保存的工作历史和序号。
     */
    public static LoopState initial(List<WorkingMessage> messages, long nextSequence, long stableSequence) {
        return new LoopState(
                messages,
                nextSequence,
                stableSequence,
                ACTIVE,
                null,
                1,
                0,
                null,
                null,
                null,
                null
        );
    }

    /**
     * 返回当前 process 内唯一工作历史的只读视图。
     */
    public List<WorkingMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    /**
     * 提取 LangChain4j 消息，供工具协调器和长期记忆提取使用。
     */
    public List<ChatMessage> chatMessages() {
        return WorkingMessage.unwrap(messages);
    }

    /**
     * 返回已经分配的最大原始消息序号，供 process 结束时回交 AgentLoop。
     */
    public long nextSequence() {
        return nextSequence;
    }

    /**
     * 返回最近一次完整模型请求的容量状态。
     */
    public ContextBudgetService.CapacityState capacityState() {
        return capacityState;
    }

    /**
     * 更新最近容量判断并返回当前状态对象。
     */
    public LoopState withCapacityState(ContextBudgetService.CapacityState capacityState) {
        this.capacityState = capacityState;
        return this;
    }

    /**
     * 为一条新产生的原始消息分配递增 sequence 并追加到当前历史。
     */
    public LoopState appendOriginal(ChatMessage message) {
        messages.add(WorkingMessage.original(++nextSequence, message));
        return this;
    }

    /**
     * 用压缩后历史替换当前上下文，原始消息序号游标保持单调不回退。
     */
    public LoopState withMessages(List<WorkingMessage> newMessages) {
        messages = new ArrayList<>(newMessages);
        return this;
    }

    /**
     * 标记当前没有执行中的工具批次，并把最后分配的原始序号作为稳定上界。
     */
    public LoopState markStable() {
        currentStableSequence = nextSequence;
        return this;
    }

    /**
     * 用当前稳定上界和工作历史创建后台摘要不可变快照。
     */
    public StableHistorySnapshot stableSnapshot() {
        if (currentStableSequence <= 0) {
            throw new IllegalStateException("no stable original message is available");
        }
        return new StableHistorySnapshot(currentStableSequence, messages);
    }

    /**
     * 返回 ACTIVE 或 TERMINAL_* 循环状态。
     */
    public String state() {
        return state;
    }

    /**
     * 判断当前状态是否禁止继续进入模型轮次。
     */
    public boolean isTerminal() {
        return state != null && state.startsWith("TERMINAL_");
    }

    /**
     * 判断当前状态是否允许继续准备模型请求。
     */
    public boolean isActive() {
        return ACTIVE.equals(state);
    }

    /**
     * 返回最近状态迁移的稳定原因码。
     */
    public String transitionReason() {
        return transitionReason;
    }

    /**
     * 返回当前 process 即将执行或已经完成的主模型轮次计数。
     */
    public int turnCount() {
        return turnCount;
    }

    /**
     * 返回连续主模型调用失败次数。
     */
    public int failureCount() {
        return failureCount;
    }

    /**
     * 返回最近一次成功准备并发送的完整模型请求。
     */
    public ChatRequest request() {
        return request;
    }

    /**
     * 返回最近一次主模型响应中的助手消息。
     */
    public AiMessage aiMessage() {
        return aiMessage;
    }

    /**
     * 返回最近权限检查通过的工具请求副本；当前循环未持有时返回空引用。
     */
    public List<ToolExecutionRequest> approvedRequests() {
        return approvedRequests == null ? null : List.copyOf(approvedRequests);
    }

    /**
     * 替换循环状态和迁移原因，保留当前上下文及轮次数据。
     */
    public LoopState withState(String newState, String newReason) {
        state = newState;
        transitionReason = newReason;
        return this;
    }

    /**
     * 进入指定 TERMINAL_* 状态并记录原因。
     */
    public LoopState withTerminalState(String terminalState, String reason) {
        return withState(terminalState, reason);
    }

    /**
     * 更新主模型轮次计数，供完整工具批次结束后进入下一轮。
     */
    public LoopState withTurnCount(int newTurnCount) {
        turnCount = newTurnCount;
        return this;
    }

    /**
     * 更新连续模型失败计数，成功响应后由主循环归零。
     */
    public LoopState withFailureCount(int newFailureCount) {
        failureCount = newFailureCount;
        return this;
    }

    /**
     * 保存最近一次准备完成的模型请求，便于终态和诊断读取。
     */
    public LoopState withRequest(ChatRequest newRequest) {
        request = newRequest;
        return this;
    }

    /**
     * 保存或清除最近助手消息，不改变 Working History。
     */
    public LoopState withAiMessage(AiMessage newAiMessage) {
        aiMessage = newAiMessage;
        return this;
    }

    /**
     * 保存或清除当前轮已批准工具请求的防御性副本。
     */
    public LoopState withApprovedRequests(List<ToolExecutionRequest> requests) {
        approvedRequests = requests == null ? null : List.copyOf(requests);
        return this;
    }
}
