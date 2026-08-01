package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.TokenEstimator;
import cn.ayice.veyra.context.WorkingMessage;
import cn.ayice.veyra.llm.AIService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 第三级上下文压缩器，在稳定用户回合边界上摘要完整旧历史并原样保留最近历史。
 */
public final class LlmSummaryCompactor {

    public static final int DEFAULT_MAX_CHUNK_INPUT_TOKENS = 100_000;
    public static final int DEFAULT_KEEP_RECENT_MESSAGES = 10;
    public static final int DEFAULT_MAX_OUTPUT_TOKENS = 3_000;
    public static final int DEFAULT_RETRY_OUTPUT_TOKENS = 1_800;

    private final AIService ai;
    private final ConversationChunker chunker;
    private final int maxChunkInputTokens;
    private final int keepRecentMessages;

    public LlmSummaryCompactor(AIService ai, ConversationChunker chunker) {
        this(ai, chunker, DEFAULT_MAX_CHUNK_INPUT_TOKENS, DEFAULT_KEEP_RECENT_MESSAGES);
    }

    public LlmSummaryCompactor(
            AIService ai,
            ConversationChunker chunker,
            int maxChunkInputTokens,
            int keepRecentMessages
    ) {
        this.ai = Objects.requireNonNull(ai, "ai");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        if (maxChunkInputTokens <= 0 || keepRecentMessages <= 0) {
            throw new IllegalArgumentException("invalid LLM summary compaction limits");
        }
        this.maxChunkInputTokens = maxChunkInputTokens;
        this.keepRecentMessages = keepRecentMessages;
    }

    /**
     * 使用默认输出上限执行完整摘要压缩。
     */
    public CompactionResult compact(
            List<WorkingMessage> messages,
            CompactTrigger trigger,
            int preCompactTokens
    ) {
        return compact(messages, trigger, preCompactTokens, DEFAULT_MAX_OUTPUT_TOKENS);
    }

    /**
     * 使用指定输出上限执行完整摘要压缩，供最终预算不足时做一次更短重生成。
     */
    public CompactionResult compact(
            List<WorkingMessage> messages,
            CompactTrigger trigger,
            int preCompactTokens,
            int maxOutputTokens
    ) {
        Objects.requireNonNull(messages, "messages");
        Objects.requireNonNull(trigger, "trigger");
        if (maxOutputTokens <= 0) {
            throw new IllegalArgumentException("maxOutputTokens must be positive");
        }

        List<WorkingMessage> visibleHistory = afterLastFullBoundary(messages);
        int keepFrom = findRecentStart(visibleHistory);
        if (keepFrom <= 0) {
            return new CompactionResult(messages, CompactStrategy.NONE, Optional.empty());
        }
        List<WorkingMessage> oldHistory = visibleHistory.subList(0, keepFrom).stream()
                .filter(message -> !CompactBoundary.isBoundary(message.message()))
                .toList();
        List<WorkingMessage> recent = List.copyOf(visibleHistory.subList(keepFrom, visibleHistory.size()));
        long coveredSequence = oldHistory.stream()
                .filter(message -> message.sequence().isPresent())
                .mapToLong(message -> message.sequence().getAsLong())
                .max()
                .orElse(0L);
        if (oldHistory.isEmpty() || coveredSequence == 0) {
            return new CompactionResult(messages, CompactStrategy.NONE, Optional.empty());
        }

        List<List<WorkingMessage>> chunks = chunker.split(oldHistory, maxChunkInputTokens);
        List<String> partialSummaries = chunks.stream()
                .map(chunk -> callModel(
                        CompactPrompts.buildChunkSummaryPrompt(SessionSummaryGenerator.formatMessages(chunk)),
                        maxOutputTokens
                ))
                .toList();
        String summary = partialSummaries.size() == 1
                ? partialSummaries.get(0)
                : callModel(CompactPrompts.buildMergePrompt("", partialSummaries), maxOutputTokens);
        if (TokenEstimator.estimateText(summary) > maxOutputTokens) {
            throw new IllegalStateException("LLM_SUMMARY_OUTPUT_TOO_LARGE");
        }

        List<WorkingMessage> compacted = new ArrayList<>(recent.size() + 2);
        compacted.add(WorkingMessage.synthetic(CompactBoundary.create(
                trigger.name().toLowerCase() + ":llm_summary",
                preCompactTokens,
                oldHistory.size()
        )));
        compacted.add(WorkingMessage.synthetic(UserMessage.from("""
                <conversation-summary covered-sequence="%d">
                %s
                </conversation-summary>
                """.formatted(coveredSequence, summary))));
        compacted.addAll(recent);
        return new CompactionResult(
                compacted,
                CompactStrategy.LLM_SUMMARY,
                Optional.of(new CheckpointCandidate(summary, coveredSequence))
        );
    }

    /**
     * 发起一次禁用工具的摘要请求，并拒绝空响应。
     */
    private String callModel(String prompt, int maxOutputTokens) {
        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from("你是 Veyra 的上下文压缩摘要器，只能返回摘要文本，不能调用工具。"),
                        UserMessage.from(prompt)
                )
                .maxOutputTokens(maxOutputTokens)
                .build();
        String result = ai.chat(request).aiMessage().text();
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("LLM_SUMMARY_EMPTY_RESPONSE");
        }
        return result.trim();
    }

    /**
     * 截取最近完整压缩边界之后的可见工作历史，避免旧摘要被重复展开。
     */
    private List<WorkingMessage> afterLastFullBoundary(List<WorkingMessage> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (CompactBoundary.isFullBoundary(messages.get(index).message())) {
                return List.copyOf(messages.subList(index + 1, messages.size()));
            }
        }
        return List.copyOf(messages);
    }

    /**
     * 以最近消息数为目标向前调整到真实用户回合起点，保证工具批次不会被切开。
     */
    private int findRecentStart(List<WorkingMessage> messages) {
        int target = Math.max(0, messages.size() - keepRecentMessages);
        if (target == 0) {
            return 0;
        }
        for (int index = target; index >= 0; index--) {
            WorkingMessage message = messages.get(index);
            if (message.sequence().isPresent() && message.message() instanceof UserMessage) {
                return index;
            }
        }
        return 0;
    }
}
