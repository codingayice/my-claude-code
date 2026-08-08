package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.context.TokenEstimator;
import cn.ayice.veyra.context.WorkingMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 单轮模型调用前的上下文准备器，集中编排压缩、恢复、完整请求预算和结构验证。
 */
public final class CompactionService {

    private static final int MAX_MODIFIED_FILE_HINTS = 5;
    private static final int REACTIVE_TARGET_BUFFER_TOKENS = 5_000;

    private final ContextService contextBuilder;
    private final CompactionConfig compactConfig;
    private final MicroCompactor microCompactor;
    private final SessionSummaryState summaryState;
    private final SummaryCompactor summaryCompactor;
    private final CompactionService.ModifiedFiles modifiedFileSource;

    /**
     * 新压缩链路使用的完整依赖构造器。
     */
    public CompactionService(
            ContextService contextBuilder,
            CompactionConfig compactConfig,
            MicroCompactor microCompactor,
            SessionSummaryState summaryState,
            SummaryCompactor summaryCompactor,
            CompactionService.ModifiedFiles modifiedFileSource
    ) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.compactConfig = Objects.requireNonNull(compactConfig, "compactConfig");
        this.microCompactor = Objects.requireNonNull(microCompactor, "microCompactor");
        this.summaryState = summaryState;
        this.summaryCompactor = Objects.requireNonNull(summaryCompactor, "summaryCompactor");
        this.modifiedFileSource = Objects.requireNonNull(modifiedFileSource, "modifiedFileSource");
    }

    /**
     * 按触发方式运行增强压缩管线，并只在最终请求满足预算和结构约束后返回。
     */
    public PreparedWorkingTurn prepareWorking(
            List<WorkingMessage> currentMessages,
            CompactionService.Trigger trigger,
            long completedModelRounds,
            Path workingDir
    ) {
        List<WorkingMessage> original = List.copyOf(currentMessages);
        List<WorkingMessage> messages = original;
        CompactionService.Strategy strategy = CompactionService.Strategy.NONE;
        Optional<SessionSummaryState.SummaryCandidate> summaryCandidate = Optional.empty();
        boolean strictLlmSummaryUsed = trigger == CompactionService.Trigger.REACTIVE;

        ChatRequest request = buildWorkingRequest(messages, workingDir);
        int inputTokens = measureRequest(request);
        int preCompactInputTokens = inputTokens;
        CapacityState capacityState = classify(inputTokens, compactConfig);

        if (trigger == CompactionService.Trigger.AUTO && compactConfig.isAutoCompactEnabled()) {
            if (compactConfig.isMicroCompactEnabled()) {
                cn.ayice.veyra.compaction.CompactionService.Result micro =
                        microCompactor.compact(messages, completedModelRounds);
                messages = micro.messages();
                strategy = micro.strategy();
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = measureRequest(request);
                capacityState = classify(inputTokens, compactConfig);
            }
            if (capacityState == CapacityState.COMPACT_REQUIRED
                    && summaryState != null && summaryState.current().isPresent()) {
                messages = applySessionSummary(messages, summaryState.current().orElseThrow(), inputTokens);
                strategy = CompactionService.Strategy.SESSION_SUMMARY;
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = measureRequest(request);
                capacityState = classify(inputTokens, compactConfig);
            }
        }

        boolean requiresLlmSummary = trigger != CompactionService.Trigger.AUTO
                || compactConfig.isAutoCompactEnabled()
                && capacityState == CapacityState.COMPACT_REQUIRED;
        if (requiresLlmSummary) {
            int maxOutputTokens = trigger == CompactionService.Trigger.REACTIVE
                    ? SummaryCompactor.DEFAULT_RETRY_OUTPUT_TOKENS
                    : SummaryCompactor.DEFAULT_MAX_OUTPUT_TOKENS;
            cn.ayice.veyra.compaction.CompactionService.Result llm;
            try {
                llm = runLlmSummary(messages, trigger, inputTokens, maxOutputTokens);
            } catch (PreparationException oversizedSummary) {
                if (!"LLM_SUMMARY_OUTPUT_TOO_LARGE".equals(oversizedSummary.errorCode())
                        || strictLlmSummaryUsed) {
                    throw oversizedSummary;
                }
                llm = runLlmSummary(
                        messages,
                        trigger,
                        inputTokens,
                        SummaryCompactor.DEFAULT_RETRY_OUTPUT_TOKENS
                );
                strictLlmSummaryUsed = true;
            }
            if (llm.strategy() == CompactionService.Strategy.LLM_SUMMARY) {
                messages = llm.messages();
                strategy = llm.strategy();
                summaryCandidate = llm.summaryCandidate();
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = measureRequest(request);
                capacityState = classify(inputTokens, compactConfig);
            }
        }

        WorkingMessage restoration = null;
        if (strategy == CompactionService.Strategy.SESSION_SUMMARY || strategy == CompactionService.Strategy.LLM_SUMMARY) {
            restoration = buildRestorationMessage();
            if (restoration != null) {
                List<WorkingMessage> withRestoration = insertAfterSummary(messages, restoration);
                ChatRequest restoredRequest = buildWorkingRequest(withRestoration, workingDir);
                int restoredTokens = measureRequest(restoredRequest);
                if (classify(restoredTokens, compactConfig) != CapacityState.COMPACT_REQUIRED) {
                    messages = withRestoration;
                    request = restoredRequest;
                    inputTokens = restoredTokens;
                    capacityState = classify(restoredTokens, compactConfig);
                }
            }
        }

        boolean llmSummaryMissedTarget = strategy == CompactionService.Strategy.LLM_SUMMARY
                && (capacityState == CapacityState.COMPACT_REQUIRED
                || trigger == CompactionService.Trigger.AUTO
                && inputTokens >= compactConfig.warningThreshold()
                || trigger == CompactionService.Trigger.REACTIVE
                && inputTokens >= Math.max(1, compactConfig.warningThreshold() - REACTIVE_TARGET_BUFFER_TOKENS));
        if (llmSummaryMissedTarget && !strictLlmSummaryUsed) {
            cn.ayice.veyra.compaction.CompactionService.Result stricter =
                    runLlmSummary(
                            original,
                            trigger,
                            measureRequest(buildWorkingRequest(original, workingDir)),
                            SummaryCompactor.DEFAULT_RETRY_OUTPUT_TOKENS
                    );
            if (stricter.strategy() == CompactionService.Strategy.LLM_SUMMARY) {
                strictLlmSummaryUsed = true;
                messages = stricter.messages();
                summaryCandidate = stricter.summaryCandidate();
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = measureRequest(request);
                capacityState = classify(inputTokens, compactConfig);
            }
        }

        if (capacityState == CapacityState.COMPACT_REQUIRED) {
            throw new PreparationException("COMPACTION_INSUFFICIENT");
        }
        if (trigger == CompactionService.Trigger.REACTIVE
                && inputTokens >= Math.max(1, compactConfig.warningThreshold() - REACTIVE_TARGET_BUFFER_TOKENS)) {
            throw new PreparationException("COMPACTION_INSUFFICIENT");
        }
        ValidationResult validation = validateRequest(request);
        if (!validation.valid()) {
            throw new PreparationException(validation.errorCode());
        }
        SessionSummaryState.CommitResult commitResult = null;
        if (summaryCandidate.isPresent() && summaryState != null) {
            commitResult = summaryState.commit(summaryCandidate.orElseThrow());
            if (commitResult.status() == SessionSummaryState.CommitStatus.SKIPPED_CLOSED) {
                throw new PreparationException("SESSION_CLOSED");
            }
        }
        return new PreparedWorkingTurn(
                messages,
                request,
                preCompactInputTokens,
                inputTokens,
                capacityState,
                strategy,
                commitResult == null ? null : commitResult.status(),
                commitResult == null || commitResult.status() != SessionSummaryState.CommitStatus.COMMITTED
                        ? null
                        : commitResult.summary().orElseThrow().summaryVersion()
        );
    }

    /**
     * 只构造并计量当前完整请求，供 /compact status 查询，不执行任何压缩或提交。
     */
    public CapacityInfo inspect(List<WorkingMessage> messages, Path workingDir) {
        ChatRequest request = buildWorkingRequest(messages, workingDir);
        int inputTokens = measureRequest(request);
        return new CapacityInfo(inputTokens, classify(inputTokens, compactConfig));
    }

    /**
     * 将摘要模型和输出预算失败转换为稳定准备错误，避免供应商异常穿透到控制层。
     */
    private cn.ayice.veyra.compaction.CompactionService.Result runLlmSummary(
            List<WorkingMessage> messages,
            CompactionService.Trigger trigger,
            int inputTokens,
            int maxOutputTokens
    ) {
        try {
            return summaryCompactor.compact(messages, trigger, inputTokens, maxOutputTokens);
        } catch (IllegalStateException failure) {
            String code = "LLM_SUMMARY_OUTPUT_TOO_LARGE".equals(failure.getMessage())
                    ? "LLM_SUMMARY_OUTPUT_TOO_LARGE"
                    : "SUMMARY_GENERATION_FAILED";
            throw new PreparationException(code, failure);
        } catch (RuntimeException failure) {
            throw new PreparationException("SUMMARY_GENERATION_FAILED", failure);
        }
    }

    /**
     * 用已提交 Session Summary 替换 coveredSequence 以内的历史，并保留其后原始消息。
     */
    private List<WorkingMessage> applySessionSummary(
            List<WorkingMessage> messages,
            SessionSummaryState.SummarySnapshot summary,
            int preCompactTokens
    ) {
        List<WorkingMessage> recent = messages.stream()
                .filter(message -> message.sequence().isPresent())
                .filter(message -> message.sequence().getAsLong() > summary.coveredSequence())
                .toList();
        List<WorkingMessage> result = new ArrayList<>(recent.size() + 2);
        result.add(WorkingMessage.synthetic(CompactBoundary.create(
                "auto:session_summary",
                preCompactTokens,
                messages.size() - recent.size()
        )));
        result.add(WorkingMessage.synthetic(UserMessage.from("""
                <session-summary version="%d" covered-sequence="%d">
                %s
                </session-summary>
                """.formatted(
                summary.summaryVersion(),
                summary.coveredSequence(),
                summary.summaryText()
        ))));
        result.addAll(recent);
        return List.copyOf(result);
    }

    /**
     * 把最近成功修改的文件路径合并为一条重新 Read 提示，不读取或注入文件正文。
     */
    private WorkingMessage buildRestorationMessage() {
        List<Path> paths = modifiedFileSource.recentModifiedPaths(MAX_MODIFIED_FILE_HINTS);
        if (paths.isEmpty()) {
            return null;
        }
        String formattedPaths = paths.stream()
                .map(path -> "- " + path)
                .collect(Collectors.joining("\n"));
        return WorkingMessage.synthetic(UserMessage.from("""
                <context-restoration>
                以下文件在当前 Session 中修改过。继续操作前请按需重新 Read 当前磁盘内容：

                %s
                </context-restoration>
                """.formatted(formattedPaths)));
    }

    /**
     * 将文件恢复参考放在 Full Boundary 和摘要参考之后、recent 原始消息之前。
     */
    private List<WorkingMessage> insertAfterSummary(
            List<WorkingMessage> messages,
            WorkingMessage restoration
    ) {
        List<WorkingMessage> result = new ArrayList<>(messages);
        int insertionIndex = Math.min(2, result.size());
        result.add(insertionIndex, restoration);
        return List.copyOf(result);
    }

    /**
     * 排除最近 Full Boundary 本身后构造实际模型请求，边界只保留在 Working History 中。
     */
    private ChatRequest buildWorkingRequest(List<WorkingMessage> messages, Path workingDir) {
        int boundaryIndex = -1;
        for (int index = messages.size() - 1; index >= 0; index--) {
            if (CompactBoundary.isFullBoundary(messages.get(index).message())) {
                boundaryIndex = index;
                break;
            }
        }
        List<WorkingMessage> visible = boundaryIndex < 0
                ? messages
                : messages.subList(boundaryIndex + 1, messages.size());
        return contextBuilder.buildWorking(visible, workingDir);
    }

    /**
     * 一次成功准备的不可分结果，包含最终历史、请求、预算、策略和摘要提交信息。
     */
    public record PreparedWorkingTurn(
            List<WorkingMessage> messages,
            ChatRequest request,
            int preCompactInputTokens,
            int inputTokens,
            CapacityState capacityState,
            CompactionService.Strategy strategy,
            SessionSummaryState.CommitStatus summaryCommit,
            Long summaryVersion
    ) {
        public PreparedWorkingTurn {
            messages = List.copyOf(messages);
        }
    }

    /**
     * `/compact status` 使用的只读完整请求容量结果。
     */
    public record CapacityInfo(
            int inputTokens,
            CapacityState capacityState
    ) {
    }

    /**
     * 压缩触发来源。
     */
    public enum Trigger {
        AUTO,
        MANUAL,
        REACTIVE
    }

    /**
     * 本轮最终采用的压缩策略。
     */
    public enum Strategy {
        NONE,
        MICRO,
        SESSION_SUMMARY,
        LLM_SUMMARY
    }

    /**
     * 单步压缩算法的内部结果。
     */
    record Result(
            List<WorkingMessage> messages,
            Strategy strategy,
            Optional<SessionSummaryState.SummaryCandidate> summaryCandidate
    ) {
        Result {
            messages = List.copyOf(messages);
            summaryCandidate = summaryCandidate == null ? Optional.empty() : summaryCandidate;
        }
    }

    /**
     * 提供当前会话最近修改文件路径的最小回调。
     */
    @FunctionalInterface
    public interface ModifiedFiles {
        /**
         * 返回不超过指定数量的最近修改路径。
         */
        List<Path> recentModifiedPaths(int limit);
    }

    /**
     * 一次完整输入请求所处的容量区间。
     */
    public enum CapacityState {
        NORMAL,
        WARNING,
        COMPACT_REQUIRED
    }

    /**
     * 计量完整请求中的消息和工具 Schema。
     */
    static int measureRequest(ChatRequest request) {
        Objects.requireNonNull(request, "request");
        long total = TokenEstimator.estimate(request.messages());
        List<ToolSpecification> specifications = request.toolSpecifications();
        if (specifications != null) {
            for (ToolSpecification specification : specifications) {
                total += TokenEstimator.estimateText(specification.toString());
            }
        }
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * 按压缩配置中的固定水位分类完整请求。
     */
    static CapacityState classify(int inputTokens, CompactionConfig config) {
        if (inputTokens >= config.threshold()) {
            return CapacityState.COMPACT_REQUIRED;
        }
        if (inputTokens >= config.warningThreshold()) {
            return CapacityState.WARNING;
        }
        return CapacityState.NORMAL;
    }

    /**
     * 验证工具调用 ID 唯一、请求结果完整且结果顺序与请求顺序一致。
     */
    static ValidationResult validateRequest(ChatRequest request) {
        Set<String> requestIds = new HashSet<>();
        Set<String> resultIds = new HashSet<>();
        List<String> requestOrder = new ArrayList<>();
        List<String> resultOrder = new ArrayList<>();
        for (ChatMessage message : request.messages()) {
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    if (!requestIds.add(toolRequest.id())) {
                        return ValidationResult.invalid("DUPLICATE_TOOL_USE", toolRequest.id());
                    }
                    requestOrder.add(toolRequest.id());
                }
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                if (!requestIds.contains(toolResult.id())) {
                    return ValidationResult.invalid("ORPHAN_TOOL_RESULT", toolResult.id());
                }
                if (!resultIds.add(toolResult.id())) {
                    return ValidationResult.invalid("DUPLICATE_TOOL_RESULT", toolResult.id());
                }
                resultOrder.add(toolResult.id());
            }
        }
        for (String requestId : requestOrder) {
            if (!resultIds.contains(requestId)) {
                return ValidationResult.invalid("MISSING_TOOL_RESULT", requestId);
            }
        }
        return requestOrder.equals(resultOrder)
                ? ValidationResult.success()
                : ValidationResult.invalid("TOOL_RESULT_ORDER", "");
    }

    /**
     * 内部请求结构验证结果。
     */
    record ValidationResult(boolean valid, String errorCode, String toolCallId) {
        /**
         * 创建结构校验成功结果。
         */
        static ValidationResult success() {
            return new ValidationResult(true, null, null);
        }

        /**
         * 创建包含稳定错误码和工具调用 ID 的失败结果。
         */
        static ValidationResult invalid(String errorCode, String toolCallId) {
            return new ValidationResult(false, errorCode, toolCallId);
        }
    }

    /**
     * 在发送主模型请求前阻断流程的稳定压缩错误，不携带用户消息或模型原始响应。
     */
    public static final class PreparationException extends RuntimeException {
        private final String errorCode;

        PreparationException(String errorCode) {
            super(errorCode);
            this.errorCode = errorCode;
        }

        /**
         * 使用稳定错误码包装摘要模型或请求构建的原始异常链。
         */
        PreparationException(String errorCode, Throwable cause) {
            super(errorCode, cause);
            this.errorCode = errorCode;
        }

        /**
         * 返回供事件、日志和控制命令使用的稳定错误码。
         */
        public String errorCode() {
            return errorCode;
        }
    }

}
