package cn.ayice.veyra.session.persistence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 从稳定事件确定性构建或增量推进 SessionIndex。 */
public final class SessionIndexProjector {

    /** 从完整事件流构建并校验 SessionIndex。 */
    public SessionIndex project(String sessionId, List<SessionJournalEntry> events) {
        SessionIndex index = SessionIndex.empty(sessionId);
        for (SessionJournalEntry event : events) {
            index = apply(index, event);
        }
        validateGraph(index);
        return index;
    }

    /** 将一个严格连续事件增量应用到已有 SessionIndex。 */
    public SessionIndex apply(SessionIndex index, SessionJournalEntry event) {
        if (!index.sessionId().equals(event.sessionId())) {
            throw new IllegalStateException("event belongs to another session");
        }
        if (event.sequence() != index.appliedRevision() + 1) {
            throw new IllegalStateException("event revision is not contiguous");
        }
        Map<String, RunIndexEntry> runs = new LinkedHashMap<>(index.runs());
        String current = index.currentRunId();
        String active = index.activeRunId();

        if (SessionJournalTypes.RUN_STARTED.equals(event.type())) {
            if (active != null || event.runId() == null || runs.containsKey(event.runId())) {
                throw new IllegalStateException("invalid run.accepted transition");
            }
            String parent = text(event.payload(), "parentRunId");
            if (parent != null) {
                RunIndexEntry parentEntry = runs.get(parent);
                if (parentEntry == null || !parentEntry.terminal()) {
                    throw new IllegalStateException("parent run is not terminal: " + parent);
                }
            }
            runs.put(event.runId(), new RunIndexEntry(
                    event.runId(), parent, event.sequence(), null, "running", false
            ));
            active = event.runId();
        } else if (SessionJournalTypes.RUN_TERMINALS.contains(event.type())) {
            RunIndexEntry run = runs.get(event.runId());
            if (run == null || run.terminal() || !event.runId().equals(active)) {
                throw new IllegalStateException("invalid run terminal transition: " + event.runId());
            }
            runs.put(event.runId(), new RunIndexEntry(
                    run.runId(), run.parentRunId(), run.startedRevision(), event.sequence(),
                    event.type().substring("run.".length()), false
            ));
            active = null;
            current = event.runId();
        } else if (SessionJournalTypes.CHECKPOINT_RESTORED.equals(event.type())) {
            if (active != null) {
                throw new IllegalStateException("cannot restore checkpoint while a run is active");
            }
            String target = text(event.payload(), "checkpointRunId");
            RunIndexEntry targetRun = runs.get(target);
            if (targetRun == null || !targetRun.terminal()) {
                throw new IllegalStateException("checkpoint run is not terminal: " + target);
            }
            current = target;
        }

        return new SessionIndex(
                SessionIndex.CURRENT_SCHEMA_VERSION,
                index.sessionId(),
                event.sequence(),
                current,
                active,
                runs
        );
    }

    /** 标记指定终态 Run 已生成可用 Snapshot。 */
    public SessionIndex markSnapshotAvailable(SessionIndex index, String runId) {
        RunIndexEntry run = index.runs().get(runId);
        if (run == null || !run.terminal()) {
            throw new IllegalStateException("cannot mark snapshot for non-terminal run: " + runId);
        }
        Map<String, RunIndexEntry> runs = new LinkedHashMap<>(index.runs());
        runs.put(runId, new RunIndexEntry(
                run.runId(), run.parentRunId(), run.startedRevision(), run.terminalRevision(), run.status(), true
        ));
        return new SessionIndex(index.schemaVersion(), index.sessionId(), index.appliedRevision(),
                index.currentRunId(), index.activeRunId(), runs);
    }

    /** 返回从根节点到目标 Run 的有序路径。 */
    public List<String> pathTo(SessionIndex index, String runId) {
        if (runId == null) {
            return List.of();
        }
        List<String> reverse = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        String cursor = runId;
        while (cursor != null) {
            if (!visited.add(cursor)) {
                throw new IllegalStateException("run graph contains a cycle");
            }
            RunIndexEntry node = index.runs().get(cursor);
            if (node == null) {
                throw new IllegalStateException("run graph references missing node: " + cursor);
            }
            reverse.add(cursor);
            cursor = node.parentRunId();
        }
        Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    /** 校验所有父引用、当前指针和活动指针组成合法无环图。 */
    public void validateGraph(SessionIndex index) {
        for (String runId : index.runs().keySet()) {
            pathTo(index, runId);
        }
        if (index.currentRunId() != null && !index.runs().containsKey(index.currentRunId())) {
            throw new IllegalStateException("currentRunId is missing from run graph");
        }
        if (index.activeRunId() != null && !index.runs().containsKey(index.activeRunId())) {
            throw new IllegalStateException("activeRunId is missing from run graph");
        }
    }

    /** 将空 payload 字段规范化为空值。 */
    private static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
