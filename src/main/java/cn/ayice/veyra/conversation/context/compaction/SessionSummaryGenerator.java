package cn.ayice.veyra.conversation.context.compaction;

import cn.ayice.veyra.conversation.context.TokenEstimator;
import cn.ayice.veyra.conversation.context.WorkingMessage;
import cn.ayice.veyra.llm.AIService;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 根据已提交检查点和新增稳定历史生成新的 Session Summary 候选，不负责触发或提交。
 */
public final class SessionSummaryGenerator {

    private final AIService ai;
    private final ConversationChunker chunker;
    private final int maxInputTokens;
    private final int maxSummaryTokens;
    private final int retrySummaryTokens;

    public SessionSummaryGenerator(AIService ai, ConversationChunker chunker) {
        this(ai, chunker, SessionSummaryConfig.defaults());
    }

    /**
     * 使用同一份 Session Summary 配置创建生成器，确保触发阈值和摘要预算不会分叉。
     */
    public SessionSummaryGenerator(
            AIService ai,
            ConversationChunker chunker,
            SessionSummaryConfig config
    ) {
        this(
                ai,
                chunker,
                config.maxInputTokens(),
                config.maxSummaryTokens(),
                config.retrySummaryTokens()
        );
    }

    public SessionSummaryGenerator(
            AIService ai,
            ConversationChunker chunker,
            int maxInputTokens,
            int maxSummaryTokens,
            int retrySummaryTokens
    ) {
        this.ai = Objects.requireNonNull(ai, "ai");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        if (maxInputTokens <= 0 || maxSummaryTokens <= 0 || retrySummaryTokens <= 0
                || retrySummaryTokens >= maxSummaryTokens) {
            throw new IllegalArgumentException("invalid session summary token limits");
        }
        this.maxInputTokens = maxInputTokens;
        this.maxSummaryTokens = maxSummaryTokens;
        this.retrySummaryTokens = retrySummaryTokens;
    }

    /**
     * 只总结检查点之后、快照稳定上界以内的增量，并返回覆盖整个快照的候选。
     */
    public CheckpointCandidate generate(
            StableHistorySnapshot snapshot,
            Optional<CompactionCheckpoint> previousCheckpoint
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        Optional<CompactionCheckpoint> previous = previousCheckpoint == null
                ? Optional.empty()
                : previousCheckpoint;
        long previousCoveredSequence = previous.map(CompactionCheckpoint::coveredSequence).orElse(0L);
        List<WorkingMessage> incremental = snapshot.messages().stream()
                .filter(message -> message.sequence().isPresent())
                .filter(message -> message.sequence().getAsLong() > previousCoveredSequence)
                .filter(message -> message.sequence().getAsLong() <= snapshot.endSequence())
                .toList();
        if (incremental.isEmpty()) {
            throw new IllegalArgumentException("snapshot has no messages after the current checkpoint");
        }

        String previousSummary = previous.map(CompactionCheckpoint::summaryText).orElse("");
        List<List<WorkingMessage>> chunks = chunker.split(incremental, maxInputTokens);
        String summary;
        if (chunks.size() == 1) {
            summary = callModel(
                    CompactPrompts.buildSessionSummaryPrompt(previousSummary, formatMessages(chunks.get(0))),
                    maxSummaryTokens
            );
        } else {
            List<String> partialSummaries = chunks.stream()
                    .map(chunk -> callModel(
                            CompactPrompts.buildChunkSummaryPrompt(formatMessages(chunk)),
                            maxSummaryTokens
                    ))
                    .toList();
            summary = callModel(
                    CompactPrompts.buildMergePrompt(previousSummary, partialSummaries),
                    maxSummaryTokens
            );
        }

        if (TokenEstimator.estimateText(summary) > maxSummaryTokens) {
            summary = callModel(CompactPrompts.buildShorterSummaryPrompt(summary), retrySummaryTokens);
        }
        if (TokenEstimator.estimateText(summary) > maxSummaryTokens) {
            throw new IllegalStateException("SESSION_SUMMARY_OUTPUT_TOO_LARGE");
        }
        return new CheckpointCandidate(summary, snapshot.endSequence());
    }

    /**
     * 使用请求级输出上限调用摘要模型，并拒绝无法形成 checkpoint 的空响应。
     */
    private String callModel(String prompt, int maxOutputTokens) {
        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from("你是 Veyra 的会话摘要生成器，只能返回摘要文本，不能调用工具。"),
                        UserMessage.from(prompt)
                )
                .maxOutputTokens(maxOutputTokens)
                .build();
        String result = ai.chat(request).aiMessage().text();
        if (result == null || result.isBlank()) {
            throw new IllegalStateException("SESSION_SUMMARY_EMPTY_RESPONSE");
        }
        return result.trim();
    }

    /**
     * 把消息转换为带角色、序号和最小工具信息的摘要输入。
     */
    static String formatMessages(List<WorkingMessage> messages) {
        return messages.stream()
                .map(SessionSummaryGenerator::formatMessage)
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * 把单条工作消息转换为包含角色、sequence 和最小工具信息的摘要数据块。
     */
    private static String formatMessage(WorkingMessage workingMessage) {
        ChatMessage message = workingMessage.message();
        String sequence = workingMessage.sequence().isPresent()
                ? Long.toString(workingMessage.sequence().getAsLong())
                : "synthetic";
        if (message instanceof UserMessage userMessage) {
            return "[用户 sequence=%s]\n%s".formatted(sequence, userMessage.singleText());
        }
        if (message instanceof AiMessage aiMessage) {
            String text = aiMessage.text() == null ? "" : aiMessage.text();
            String tools = aiMessage.hasToolExecutionRequests()
                    ? aiMessage.toolExecutionRequests().stream()
                    .map(SessionSummaryGenerator::formatToolRequest)
                    .collect(Collectors.joining("\n"))
                    : "";
            return "[助手 sequence=%s]\n%s%s".formatted(
                    sequence,
                    text,
                    tools.isBlank() ? "" : "\n" + tools
            );
        }
        if (message instanceof ToolExecutionResultMessage result) {
            return "[工具结果 sequence=%s]\nid=%s\nname=%s\ncontent=%s".formatted(
                    sequence,
                    result.id(),
                    result.toolName(),
                    MicroCompactor.truncateToolResult(result.text())
            );
        }
        return "[%s sequence=%s]\n%s".formatted(message.type(), sequence, message);
    }

    /**
     * 保留工具名称、调用 ID 和有限参数，避免把过大参数直接送入摘要请求。
     */
    private static String formatToolRequest(ToolExecutionRequest request) {
        String arguments = request.arguments();
        if (arguments != null && arguments.length() > 2_000) {
            arguments = arguments.substring(0, 2_000) + "\n[工具参数过长，已截断]";
        }
        return "[工具调用]\nname=%s\nid=%s\narguments=%s".formatted(
                request.name(),
                request.id(),
                arguments
        );
    }
}
