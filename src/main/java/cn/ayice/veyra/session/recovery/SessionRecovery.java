package cn.ayice.veyra.session.recovery;

import cn.ayice.veyra.compaction.SessionSummaryState;
import cn.ayice.veyra.session.SessionSettings;
import cn.ayice.veyra.session.persistence.JournalMessageCodec;
import cn.ayice.veyra.session.persistence.SessionJournalEntry;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import dev.langchain4j.data.message.ChatMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import cn.ayice.veyra.session.state.AgentState;
import cn.ayice.veyra.session.state.MessageRole;
import cn.ayice.veyra.session.state.MessageState;
import cn.ayice.veyra.session.state.ToolCallState;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;

/**
 * 将 Session Journal 收敛为稳定终态并重建新进程所需的恢复投影。
 */
public final class SessionRecovery {

    private static final String NOT_EXECUTED = """
            <tool-interrupted outcome="NOT_EXECUTED">
            上一次运行在工具开始执行前中断，该工具没有执行。
            </tool-interrupted>
            """.trim();

    private static final String UNKNOWN = """
            <tool-interrupted outcome="UNKNOWN">
            工具在上一次运行期间中断，可能已经产生副作用，也可能没有完成。
            系统没有自动重试。继续任务前请检查工作区和外部状态。
            </tool-interrupted>
            """.trim();

    private final SessionJournalStore store;
    private final SessionSettings defaults;

    public SessionRecovery(SessionJournalStore store, Path defaultWorkingDir, String defaultPermissionMode) {
        this.store = store;
        this.defaults = new SessionSettings(defaultWorkingDir, defaultPermissionMode, "chat");
    }

    /** 读取 Session 事实、幂等补齐悬挂终态并返回恢复投影。 */
    public RecoveryResult recover(String sessionId) {
        List<SessionJournalEntry> entries = new ArrayList<>(store.read(sessionId));
        if (entries.isEmpty()) {
            return new RecoveryResult(List.of(), Optional.empty(), defaults, "idle", false, AgentState.empty());
        }

        Map<String, ToolUse> toolUses = new LinkedHashMap<>();
        Set<String> startedTools = new HashSet<>();
        Set<String> completedTools = new HashSet<>();
        Map<String, StartedTask> startedTasks = new LinkedHashMap<>();
        Set<String> finishedTasks = new HashSet<>();
        Map<String, String> requestedApprovals = new LinkedHashMap<>();
        Set<String> resolvedApprovals = new HashSet<>();
        Map<String, String> openRuns = new LinkedHashMap<>();
        Set<String> terminalRuns = new HashSet<>();
        Map<String, String> openModelCalls = new LinkedHashMap<>();
        Set<String> closedModelCalls = new HashSet<>();

        for (SessionJournalEntry entry : entries) {
            switch (entry.type()) {
                case SessionJournalTypes.RUN_STARTED -> openRuns.put(entry.runId(), entry.runId());
                case SessionJournalTypes.RUN_COMPLETED,
                     SessionJournalTypes.RUN_FAILED,
                     SessionJournalTypes.RUN_CANCELLED,
                     SessionJournalTypes.RUN_INTERRUPTED -> terminalRuns.add(entry.runId());
                case SessionJournalTypes.MODEL_CALL_STARTED ->
                        openModelCalls.put(text(entry.payload(), "modelCallId"), entry.runId());
                case SessionJournalTypes.MODEL_CALL_FAILED, SessionJournalTypes.MODEL_CALL_INTERRUPTED ->
                        closedModelCalls.add(text(entry.payload(), "modelCallId"));
                case SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED -> {
                    collectToolUses(entry, toolUses);
                    openModelCalls.entrySet().stream()
                            .filter(call -> java.util.Objects.equals(call.getValue(), entry.runId()))
                            .map(Map.Entry::getKey)
                            .forEach(closedModelCalls::add);
                }
                case SessionJournalTypes.TOOL_EXECUTION_STARTED ->
                        startedTools.add(text(entry.payload(), "toolUseId"));
                case SessionJournalTypes.TOOL_RESULT_RECORDED ->
                        completedTools.add(text(entry.payload(), "toolUseId"));
                case SessionJournalTypes.TASK_STARTED -> {
                    String taskId = text(entry.payload(), "taskId");
                    startedTasks.put(taskId, new StartedTask(entry.runId(), text(entry.payload(), "taskType")));
                }
                case SessionJournalTypes.TASK_COMPLETED,
                     SessionJournalTypes.TASK_FAILED,
                     SessionJournalTypes.TASK_KILLED,
                     SessionJournalTypes.TASK_INTERRUPTED ->
                        finishedTasks.add(text(entry.payload(), "taskId"));
                case SessionJournalTypes.PERMISSION_REQUESTED ->
                        requestedApprovals.put(text(entry.payload(), "approvalId"), entry.runId());
                case SessionJournalTypes.PERMISSION_RESOLVED,
                     SessionJournalTypes.PERMISSION_INTERRUPTED ->
                        resolvedApprovals.add(text(entry.payload(), "approvalId"));
                default -> {
                }
            }
        }

        for (Map.Entry<String, String> modelCall : openModelCalls.entrySet()) {
            if (!modelCall.getKey().isBlank() && !closedModelCalls.contains(modelCall.getKey())) {
                entries.add(store.append(sessionId, modelCall.getValue(),
                        SessionJournalTypes.MODEL_CALL_INTERRUPTED,
                        Map.of("modelCallId", modelCall.getKey(), "reason", "process_terminated"), true));
            }
        }

        for (ToolUse tool : toolUses.values()) {
            if (completedTools.contains(tool.id())) {
                continue;
            }
            boolean started = startedTools.contains(tool.id());
            String outcome = started ? "UNKNOWN" : "NOT_EXECUTED";
            SessionJournalEntry repair = store.append(
                    sessionId,
                    tool.runId(),
                    SessionJournalTypes.TOOL_RESULT_RECORDED,
                    Map.of(
                            "toolUseId", tool.id(),
                            "name", tool.name(),
                            "success", false,
                            "outcome", outcome,
                            "content", started ? UNKNOWN : NOT_EXECUTED
                    ),
                    true
            );
            entries.add(repair);
        }

        for (Map.Entry<String, StartedTask> task : startedTasks.entrySet()) {
            if (finishedTasks.contains(task.getKey())) {
                continue;
            }
            SessionJournalEntry repair = store.append(
                    sessionId,
                    task.getValue().runId(),
                    SessionJournalTypes.TASK_INTERRUPTED,
                    Map.of(
                            "taskId", task.getKey(),
                            "status", "interrupted",
                            "content", "因后端进程退出而中断，未自动重新创建"
                    ),
                    true
            );
            entries.add(repair);
        }

        for (Map.Entry<String, String> approval : requestedApprovals.entrySet()) {
            if (resolvedApprovals.contains(approval.getKey())) {
                continue;
            }
            SessionJournalEntry repair = store.append(
                    sessionId,
                    approval.getValue(),
                    SessionJournalTypes.PERMISSION_INTERRUPTED,
                    Map.of("approvalId", approval.getKey(), "decision", "interrupted"),
                    true
            );
            entries.add(repair);
        }

        for (String runId : openRuns.keySet()) {
            if (terminalRuns.contains(runId)) {
                continue;
            }
            SessionJournalEntry repair = store.append(
                    sessionId,
                    runId,
                    SessionJournalTypes.RUN_INTERRUPTED,
                    Map.of("reason", "process_terminated"),
                    true
            );
            entries.add(repair);
        }

        return project(store.recoveryEvents(sessionId), defaults, store.recoveryAgentState(sessionId));
    }

    /** 从指定终态 Run 的不可变路径状态恢复，不改变当前指针。 */
    public RecoveryResult recoverAt(String sessionId, String runId) {
        return project(store.recoveryEventsAt(sessionId, runId), defaults, store.recoveryAgentStateAt(sessionId, runId));
    }

    /** 从最终稳定事实一次性构建 Runtime 和 UI 所需的只读投影。 */
    private static RecoveryResult project(
            List<SessionJournalEntry> entries,
            SessionSettings defaults,
            AgentState agentState
    ) {
        List<ChatMessage> history = new ArrayList<>(decodeHistory(agentState));
        SessionSummaryState.SummarySnapshot sessionSummary = null;
        SessionSettings settings = defaults;
        String lastRunStatus = "idle";

        for (SessionJournalEntry entry : entries) {
            if (SessionJournalTypes.SESSION_CREATED.equals(entry.type())
                    || SessionJournalTypes.SESSION_SETTINGS_UPDATED.equals(entry.type())) {
                String workingDir = text(entry.payload(), "workingDir");
                String permissionMode = text(entry.payload(), "permissionMode");
                String runMode = text(entry.payload(), "runMode");
                if (!workingDir.isBlank()) {
                    settings = new SessionSettings(
                            Path.of(workingDir),
                            permissionMode.isBlank() ? settings.permissionMode() : permissionMode,
                            runMode.isBlank() ? settings.runMode() : runMode
                    );
                }
            } else if (SessionJournalTypes.CONTEXT_SUMMARY_RECORDED.equals(entry.type())) {
                SessionSummaryState.SummarySnapshot candidate = new SessionSummaryState.SummarySnapshot(
                        text(entry.payload(), "summaryText"),
                        number(entry.payload(), "coveredSequence"),
                        number(entry.payload(), "summaryVersion")
                );
                if (sessionSummary == null || candidate.summaryVersion() > sessionSummary.summaryVersion()) {
                    sessionSummary = candidate;
                }
            }
            if (SessionJournalTypes.RUN_STARTED.equals(entry.type())) {
                lastRunStatus = "running";
            } else if (SessionJournalTypes.RUN_TERMINALS.contains(entry.type())) {
                lastRunStatus = entry.type().substring("run.".length());
            }
        }
        return new RecoveryResult(
                List.copyOf(history),
                Optional.ofNullable(sessionSummary),
                settings,
                lastRunStatus,
                true,
                agentState
        );
    }

    /** 将结构化 AgentState 恢复为 LangChain4j 模型历史。 */
    private static List<ChatMessage> decodeHistory(AgentState state) {
        List<ChatMessage> result = new ArrayList<>();
        for (MessageState message : state.messages()) {
            if (message.role() == MessageRole.USER) {
                result.add(UserMessage.from(message.text()));
            } else if (message.role() == MessageRole.ASSISTANT) {
                List<ToolExecutionRequest> calls = message.toolCalls().stream()
                        .map(tool -> ToolExecutionRequest.builder()
                                .id(tool.toolUseId()).name(tool.name()).arguments(tool.arguments()).build())
                        .toList();
                result.add(AiMessage.builder().text(message.text()).thinking(message.thinking())
                        .toolExecutionRequests(calls).build());
            } else if (message.role() == MessageRole.TOOL) {
                ToolCallState tool = state.toolCalls().values().stream()
                        .filter(candidate -> candidate.resultContent().equals(message.text()))
                        .findFirst().orElse(null);
                if (tool != null) {
                    result.add(ToolExecutionResultMessage.from(tool.toolUseId(), tool.name(), message.text()));
                }
            }
        }
        return List.copyOf(result);
    }

    /** 收集完整 Assistant payload 中声明的 ToolUse。 */
    private static void collectToolUses(SessionJournalEntry entry, Map<String, ToolUse> toolUses) {
        Object rawCalls = entry.payload().get("toolCalls");
        if (!(rawCalls instanceof List<?> calls)) {
            return;
        }
        for (Object raw : calls) {
            if (!(raw instanceof Map<?, ?> call)) {
                continue;
            }
            String id = value(call, "id");
            if (!id.isBlank()) {
                toolUses.putIfAbsent(id, new ToolUse(id, value(call, "name"), entry.runId()));
            }
        }
    }

    /** 读取字符串键 payload 的安全文本。 */
    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 读取任意 Map 的安全文本。 */
    private static String value(Map<?, ?> payload, String key) {
        Object value = payload.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 将 Jackson 数值或文本统一读取为 long。 */
    private static long number(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }

    /** 恢复阶段定位缺失结果所需的最小 ToolUse 事实。 */
    private record ToolUse(String id, String name, String runId) {
    }

    /** 恢复阶段定位悬挂任务所需的最小开始事实。 */
    private record StartedTask(String runId, String type) {
    }

    /** 新 Session Runtime 和冷加载 UI 共用的不可变恢复结果。 */
    public record RecoveryResult(
            List<ChatMessage> agentHistory,
            Optional<SessionSummaryState.SummarySnapshot> sessionSummary,
            SessionSettings settings,
            String lastRunStatus,
            boolean persisted,
            AgentState agentState
    ) {
        public RecoveryResult {
            agentHistory = List.copyOf(agentHistory);
            sessionSummary = sessionSummary == null ? Optional.empty() : sessionSummary;
            agentState = agentState == null ? AgentState.empty() : agentState;
        }
    }
}
