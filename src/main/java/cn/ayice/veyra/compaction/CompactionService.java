package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.ContextBudgetService;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.context.FinalRequestValidator;
import cn.ayice.veyra.context.WorkingMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 单轮模型调用前的上下文准备器，集中编排压缩、恢复、完整请求预算和结构验证。
 */
public final class CompactionService {

    private static final int MAX_MODIFIED_FILE_HINTS = 5;
    private static final int REACTIVE_TARGET_BUFFER_TOKENS = 5_000;

    private final ContextService contextBuilder;
    private final AutoCompactConfig compactConfig;
    private final ContextBudgetService budgetService;
    private final MicroCompactor microCompactor;
    private final SessionCheckpointState checkpointState;
    private final LlmSummaryCompactor llmSummaryCompactor;
    private final FinalRequestValidator requestValidator;
    private final ModifiedFileSource modifiedFileSource;

    /**
     * 新压缩链路使用的完整依赖构造器。
     */
    public CompactionService(
            ContextService contextBuilder,
            AutoCompactConfig compactConfig,
            ContextBudgetService budgetService,
            MicroCompactor microCompactor,
            SessionCheckpointState checkpointState,
            LlmSummaryCompactor llmSummaryCompactor,
            FinalRequestValidator requestValidator,
            ModifiedFileSource modifiedFileSource
    ) {
        this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
        this.compactConfig = Objects.requireNonNull(compactConfig, "compactConfig");
        this.budgetService = Objects.requireNonNull(budgetService, "budgetService");
        this.microCompactor = Objects.requireNonNull(microCompactor, "microCompactor");
        this.checkpointState = checkpointState;
        this.llmSummaryCompactor = Objects.requireNonNull(llmSummaryCompactor, "llmSummaryCompactor");
        this.requestValidator = Objects.requireNonNull(requestValidator, "requestValidator");
        this.modifiedFileSource = Objects.requireNonNull(modifiedFileSource, "modifiedFileSource");
    }

    /**
     * 按触发方式运行增强压缩管线，并只在最终请求满足预算和结构约束后返回。
     */
    public PreparedWorkingTurn prepareWorking(
            List<WorkingMessage> currentMessages,
            CompactTrigger trigger,
            long completedModelRounds,
            Path workingDir
    ) {
        List<WorkingMessage> original = List.copyOf(currentMessages);
        List<WorkingMessage> messages = original;
        CompactStrategy strategy = CompactStrategy.NONE;
        Optional<CheckpointCandidate> candidate = Optional.empty();
        boolean strictLlmSummaryUsed = trigger == CompactTrigger.REACTIVE;

        ChatRequest request = buildWorkingRequest(messages, workingDir);
        int inputTokens = budgetService.measure(request);
        int preCompactInputTokens = inputTokens;
        ContextBudgetService.CapacityState capacityState = budgetService.classify(inputTokens);

        if (trigger == CompactTrigger.AUTO && compactConfig.isAutoCompactEnabled()) {
            if (compactConfig.isMicroCompactEnabled()) {
                cn.ayice.veyra.compaction.CompactionResult micro =
                        microCompactor.compact(messages, completedModelRounds);
                messages = micro.messages();
                strategy = micro.strategy();
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = budgetService.measure(request);
                capacityState = budgetService.classify(inputTokens);
            }
            if (capacityState == ContextBudgetService.CapacityState.COMPACT_REQUIRED
                    && checkpointState != null && checkpointState.current().isPresent()) {
                messages = applySessionCheckpoint(messages, checkpointState.current().orElseThrow(), inputTokens);
                strategy = CompactStrategy.SESSION_SUMMARY;
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = budgetService.measure(request);
                capacityState = budgetService.classify(inputTokens);
            }
        }

        boolean requiresLlmSummary = trigger != CompactTrigger.AUTO
                || compactConfig.isAutoCompactEnabled()
                && capacityState == ContextBudgetService.CapacityState.COMPACT_REQUIRED;
        if (requiresLlmSummary) {
            int maxOutputTokens = trigger == CompactTrigger.REACTIVE
                    ? LlmSummaryCompactor.DEFAULT_RETRY_OUTPUT_TOKENS
                    : LlmSummaryCompactor.DEFAULT_MAX_OUTPUT_TOKENS;
            cn.ayice.veyra.compaction.CompactionResult llm;
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
                        LlmSummaryCompactor.DEFAULT_RETRY_OUTPUT_TOKENS
                );
                strictLlmSummaryUsed = true;
            }
            if (llm.strategy() == CompactStrategy.LLM_SUMMARY) {
                messages = llm.messages();
                strategy = llm.strategy();
                candidate = llm.checkpointCandidate();
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = budgetService.measure(request);
                capacityState = budgetService.classify(inputTokens);
            }
        }

        WorkingMessage restoration = null;
        if (strategy == CompactStrategy.SESSION_SUMMARY || strategy == CompactStrategy.LLM_SUMMARY) {
            restoration = buildRestorationMessage();
            if (restoration != null) {
                List<WorkingMessage> withRestoration = insertAfterSummary(messages, restoration);
                ChatRequest restoredRequest = buildWorkingRequest(withRestoration, workingDir);
                int restoredTokens = budgetService.measure(restoredRequest);
                if (budgetService.classify(restoredTokens) != ContextBudgetService.CapacityState.COMPACT_REQUIRED) {
                    messages = withRestoration;
                    request = restoredRequest;
                    inputTokens = restoredTokens;
                    capacityState = budgetService.classify(restoredTokens);
                }
            }
        }

        boolean llmSummaryMissedTarget = strategy == CompactStrategy.LLM_SUMMARY
                && (capacityState == ContextBudgetService.CapacityState.COMPACT_REQUIRED
                || trigger == CompactTrigger.AUTO
                && inputTokens >= budgetService.warningThreshold()
                || trigger == CompactTrigger.REACTIVE
                && inputTokens >= Math.max(1, budgetService.warningThreshold() - REACTIVE_TARGET_BUFFER_TOKENS));
        if (llmSummaryMissedTarget && !strictLlmSummaryUsed) {
            cn.ayice.veyra.compaction.CompactionResult stricter =
                    runLlmSummary(
                            original,
                            trigger,
                            budgetService.measure(buildWorkingRequest(original, workingDir)),
                            LlmSummaryCompactor.DEFAULT_RETRY_OUTPUT_TOKENS
                    );
            if (stricter.strategy() == CompactStrategy.LLM_SUMMARY) {
                strictLlmSummaryUsed = true;
                messages = stricter.messages();
                candidate = stricter.checkpointCandidate();
                request = buildWorkingRequest(messages, workingDir);
                inputTokens = budgetService.measure(request);
                capacityState = budgetService.classify(inputTokens);
            }
        }

        if (capacityState == ContextBudgetService.CapacityState.COMPACT_REQUIRED) {
            throw new PreparationException("COMPACTION_INSUFFICIENT");
        }
        if (trigger == CompactTrigger.REACTIVE
                && inputTokens >= Math.max(1, budgetService.warningThreshold() - REACTIVE_TARGET_BUFFER_TOKENS)) {
            throw new PreparationException("COMPACTION_INSUFFICIENT");
        }
        FinalRequestValidator.ValidationResult validation = requestValidator.validate(request);
        if (!validation.valid()) {
            throw new PreparationException(validation.errorCode());
        }
        SessionCheckpointState.CommitResult commitResult = null;
        if (candidate.isPresent() && checkpointState != null) {
            commitResult = checkpointState.commit(candidate.orElseThrow());
            if (commitResult.status() == SessionCheckpointState.CommitStatus.SKIPPED_CLOSED) {
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
                commitResult == null || commitResult.status() != SessionCheckpointState.CommitStatus.COMMITTED
                        ? null
                        : commitResult.checkpoint().orElseThrow().checkpointVersion()
        );
    }

    /**
     * 只构造并计量当前完整请求，供 /compact status 查询，不执行任何压缩或提交。
     */
    public CapacityInfo inspect(List<WorkingMessage> messages, Path workingDir) {
        ChatRequest request = buildWorkingRequest(messages, workingDir);
        int inputTokens = budgetService.measure(request);
        return new CapacityInfo(inputTokens, budgetService.classify(inputTokens));
    }

    /**
     * 将摘要模型和输出预算失败转换为稳定准备错误，避免供应商异常穿透到控制层。
     */
    private cn.ayice.veyra.compaction.CompactionResult runLlmSummary(
            List<WorkingMessage> messages,
            CompactTrigger trigger,
            int inputTokens,
            int maxOutputTokens
    ) {
        try {
            return llmSummaryCompactor.compact(messages, trigger, inputTokens, maxOutputTokens);
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
    private List<WorkingMessage> applySessionCheckpoint(
            List<WorkingMessage> messages,
            CompactionCheckpoint checkpoint,
            int preCompactTokens
    ) {
        List<WorkingMessage> recent = messages.stream()
                .filter(message -> message.sequence().isPresent())
                .filter(message -> message.sequence().getAsLong() > checkpoint.coveredSequence())
                .toList();
        List<WorkingMessage> result = new ArrayList<>(recent.size() + 2);
        result.add(WorkingMessage.synthetic(CompactBoundary.create(
                "auto:session_summary",
                preCompactTokens,
                messages.size() - recent.size()
        )));
        result.add(WorkingMessage.synthetic(UserMessage.from("""
                <session-summary checkpoint="%d" covered-sequence="%d">
                %s
                </session-summary>
                """.formatted(
                checkpoint.checkpointVersion(),
                checkpoint.coveredSequence(),
                checkpoint.summaryText()
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
     * 一次成功准备的不可分结果，包含最终历史、请求、预算、策略和 checkpoint 提交信息。
     */
    public record PreparedWorkingTurn(
            List<WorkingMessage> messages,
            ChatRequest request,
            int preCompactInputTokens,
            int inputTokens,
            ContextBudgetService.CapacityState capacityState,
            CompactStrategy strategy,
            SessionCheckpointState.CommitStatus checkpointCommit,
            Long checkpointVersion
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
            ContextBudgetService.CapacityState capacityState
    ) {
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
