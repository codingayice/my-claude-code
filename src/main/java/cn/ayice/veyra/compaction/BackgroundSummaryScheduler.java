package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.TokenEstimator;
import cn.ayice.veyra.context.WorkingMessage;
import dev.langchain4j.data.message.AiMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

/**
 * 当前 Session 独占的后台摘要协调器，负责触发判断、single-flight 和候选提交。
 */
public final class BackgroundSummaryScheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackgroundSummaryScheduler.class);

    private final SummaryCompactor summaryCompactor;
    private final SessionSummaryState summaryState;
    private final Executor executor;
    private final CompactionConfig.SummaryPolicy config;
    private final BiConsumer<String, Map<String, Object>> eventEmitter;

    private BackgroundSummaryScheduler.Snapshot runningSnapshot;
    private BackgroundSummaryScheduler.Snapshot dirtySnapshot;
    private boolean warningRefreshSubmitted;
    private boolean closed;

    public BackgroundSummaryScheduler(
            SummaryCompactor summaryCompactor,
            SessionSummaryState summaryState,
            Executor executor
    ) {
        this(summaryCompactor, summaryState, executor, CompactionConfig.SummaryPolicy.defaults(), (type, payload) -> { });
    }

    public BackgroundSummaryScheduler(
            SummaryCompactor summaryCompactor,
            SessionSummaryState summaryState,
            Executor executor,
            CompactionConfig.SummaryPolicy config
    ) {
        this(summaryCompactor, summaryState, executor, config, (type, payload) -> { });
    }

    /**
     * 使用当前 Session 的事件回调创建协调器，后台任务不会依赖 Kernel 或 HTTP 类型。
     */
    public BackgroundSummaryScheduler(
            SummaryCompactor summaryCompactor,
            SessionSummaryState summaryState,
            Executor executor,
            CompactionConfig.SummaryPolicy config,
            BiConsumer<String, Map<String, Object>> eventEmitter
    ) {
        this.summaryCompactor = Objects.requireNonNull(summaryCompactor, "summaryCompactor");
        this.summaryState = Objects.requireNonNull(summaryState, "summaryState");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        this.eventEmitter = Objects.requireNonNull(eventEmitter, "eventEmitter");
    }

    /**
     * 在工具批次汇合或最终回复后，按已有增量阈值尝试提交后台摘要任务。
     */
    public synchronized boolean submitStableSnapshot(BackgroundSummaryScheduler.Snapshot snapshot) {
        if (closed || !meetsIncrementalThreshold(snapshot)) {
            return false;
        }
        return submit(snapshot);
    }

    /**
     * 在主模型调用前处理 WARNING 提前生成；回到 NORMAL 后开始新的 WARNING 区间。
     */
    public synchronized boolean onPreparedCapacity(
            BackgroundSummaryScheduler.Snapshot snapshot,
            CompactionService.CapacityState capacityState
    ) {
        if (capacityState == CompactionService.CapacityState.NORMAL) {
            warningRefreshSubmitted = false;
            return false;
        }
        if (closed || capacityState != CompactionService.CapacityState.WARNING || warningRefreshSubmitted) {
            return false;
        }
        long coveredSequence = summaryState.current()
                .map(SessionSummaryState.SummarySnapshot::coveredSequence)
                .orElse(0L);
        if (coveredSequence >= snapshot.endSequence()) {
            return false;
        }
        warningRefreshSubmitted = true;
        return submit(snapshot);
    }

    /**
     * 根据当前摘要覆盖位置后的 token 增量和工具调用数判断普通后台触发条件。
     */
    private boolean meetsIncrementalThreshold(BackgroundSummaryScheduler.Snapshot snapshot) {
        long coveredSequence = summaryState.current()
                .map(SessionSummaryState.SummarySnapshot::coveredSequence)
                .orElse(0L);
        if (coveredSequence >= snapshot.endSequence()) {
            return false;
        }
        var incremental = snapshot.messages().stream()
                .filter(message -> message.sequence().isPresent())
                .filter(message -> message.sequence().getAsLong() > coveredSequence)
                .toList();
        int incrementalTokens = TokenEstimator.estimate(
                incremental.stream().map(message -> message.message()).toList()
        );
        if (coveredSequence == 0) {
            return incrementalTokens >= config.initialTokens();
        }
        long toolCalls = incremental.stream()
                .filter(message -> message.message() instanceof AiMessage ai && ai.hasToolExecutionRequests())
                .mapToLong(message -> ((AiMessage) message.message()).toolExecutionRequests().size())
                .sum();
        return toolCalls > 0
                ? incrementalTokens >= config.updateGrowthTokens()
                && toolCalls >= config.toolCallsBetweenUpdates()
                : incrementalTokens >= config.toolFreeUpdateGrowthTokens();
    }

    /**
     * 启动首个任务或把更靠后的稳定快照合并为唯一 dirty snapshot。
     */
    private boolean submit(BackgroundSummaryScheduler.Snapshot snapshot) {
        if (runningSnapshot == null) {
            runningSnapshot = snapshot;
            executor.execute(this::runCurrent);
            return true;
        }
        if (snapshot.endSequence() > runningSnapshot.endSequence()
                && (dirtySnapshot == null || snapshot.endSequence() > dirtySnapshot.endSequence())) {
            dirtySnapshot = snapshot;
            eventEmitter.accept("session_summary.coalesced", Map.of(
                    "runningEndSequence", runningSnapshot.endSequence(),
                    "dirtyEndSequence", dirtySnapshot.endSequence()
            ));
        }
        return true;
    }

    /**
     * 生成并提交 running snapshot，成功后按需串行处理最新 dirty snapshot。
     */
    private void runCurrent() {
        BackgroundSummaryScheduler.Snapshot snapshot;
        synchronized (this) {
            if (closed || runningSnapshot == null) {
                return;
            }
            snapshot = runningSnapshot;
        }
        long startedAt = System.currentTimeMillis();
        eventEmitter.accept("session_summary.started", Map.of("endSequence", snapshot.endSequence()));
        boolean succeeded = false;
        try {
            SessionSummaryState.SummaryCandidate candidate = summaryCompactor.generateSessionSummary(snapshot, summaryState.current());
            SessionSummaryState.CommitResult commitResult = summaryState.commit(candidate);
            if (commitResult.status() == SessionSummaryState.CommitStatus.COMMITTED) {
                SessionSummaryState.SummarySnapshot summary = commitResult.summary().orElseThrow();
                eventEmitter.accept("session_summary.completed", Map.of(
                        "coveredSequence", summary.coveredSequence(),
                        "summaryVersion", summary.summaryVersion(),
                        "sourceTokens", TokenEstimator.estimate(WorkingMessage.unwrap(snapshot.messages())),
                        "summaryTokens", TokenEstimator.estimateText(summary.summaryText()),
                        "durationMs", System.currentTimeMillis() - startedAt
                ));
            } else {
                long currentCoveredSequence = commitResult.summary()
                        .map(SessionSummaryState.SummarySnapshot::coveredSequence)
                        .orElse(0L);
                eventEmitter.accept("session_summary.skipped", Map.of(
                        "reason", commitResult.status(),
                        "candidateCoveredSequence", candidate.coveredSequence(),
                        "currentCoveredSequence", currentCoveredSequence
                ));
            }
            succeeded = true;
        } catch (RuntimeException failure) {
            // 后台摘要失败不修改 Working History；保留异常链供定位，后续由新触发条件重新提交。
            log.error("Session Summary 后台生成失败，coveredSequence={}", snapshot.endSequence(), failure);
            eventEmitter.accept("session_summary.failed", Map.of(
                    "endSequence", snapshot.endSequence(),
                    "errorCode", "SUMMARY_GENERATION_FAILED",
                    "durationMs", System.currentTimeMillis() - startedAt
            ));
        }

        BackgroundSummaryScheduler.Snapshot next = null;
        synchronized (this) {
            runningSnapshot = null;
            if (!closed && succeeded && dirtySnapshot != null) {
                long coveredSequence = summaryState.current()
                        .map(SessionSummaryState.SummarySnapshot::coveredSequence)
                        .orElse(0L);
                if (dirtySnapshot.endSequence() > coveredSequence) {
                    next = dirtySnapshot;
                    runningSnapshot = next;
                }
            }
            dirtySnapshot = null;
        }
        if (next != null) {
            executor.execute(this::runCurrent);
        }
    }

    /**
     * 停止接收新任务并清除尚未执行的快照引用。
     */
    @Override
    public synchronized void close() {
        closed = true;
        runningSnapshot = null;
        dirtySnapshot = null;
    }

    /**
     * 工具批次汇合或最终回复后的不可变稳定历史快照。
     */
    public record Snapshot(long endSequence, List<WorkingMessage> messages) {
        public Snapshot {
            if (endSequence <= 0) {
                throw new IllegalArgumentException("endSequence must be positive");
            }
            messages = List.copyOf(messages);
        }
    }
}
