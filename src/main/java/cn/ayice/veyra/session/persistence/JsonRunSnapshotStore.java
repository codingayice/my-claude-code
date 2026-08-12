package cn.ayice.veyra.session.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import cn.ayice.veyra.session.state.AgentState;

/** 终态 RunSnapshot 的原子 JSON 文件存储。 */
public final class JsonRunSnapshotStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    private final SessionPathResolver paths;

    public JsonRunSnapshotStore(SessionPathResolver paths) {
        this.paths = paths;
    }

    /** 从终态 Run 路径事件创建带规范化 checksum 的 Snapshot。 */
    public RunSnapshot create(
            String sessionId,
            RunIndexEntry run,
            List<SessionJournalEntry> pathEvents
    ) {
        if (!run.terminal()) {
            throw new IllegalStateException("cannot snapshot an active run");
        }
        SessionJournalEntry terminalEvent = pathEvents.stream()
                .filter(event -> run.runId().equals(event.runId()))
                .filter(event -> event.sequence() == run.terminalRevision())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("terminal event is missing"));
        AgentState agentState = new cn.ayice.veyra.session.state.SessionProjection().reduceAgent(pathEvents);
        String checksum = checksum(sessionId, run.runId(), run.terminalRevision(), terminalEvent.eventId(), agentState);
        return new RunSnapshot(
                RunSnapshot.CURRENT_SCHEMA_VERSION,
                sessionId,
                run.runId(),
                run.parentRunId(),
                run.terminalRevision(),
                terminalEvent.eventId(),
                agentState,
                checksum
        );
    }

    /** 原子写入一个不可变 RunSnapshot。 */
    public synchronized void write(RunSnapshot snapshot) {
        JsonSessionIndexStore.atomicWrite(paths.runSnapshotPath(snapshot.sessionId(), snapshot.runId()), snapshot);
    }

    /** 读取并校验 Snapshot 身份和 checksum，无效时返回空。 */
    public synchronized Optional<RunSnapshot> read(String sessionId, String runId) {
        Path path = paths.runSnapshotPath(sessionId, runId);
        if (!Files.exists(path)) {
            return Optional.empty();
        }
        try {
            RunSnapshot snapshot = MAPPER.readValue(path.toFile(), RunSnapshot.class);
            if (!sessionId.equals(snapshot.sessionId()) || !runId.equals(snapshot.runId())) {
                return Optional.empty();
            }
            String actual = checksum(sessionId, runId, snapshot.terminalRevision(),
                    snapshot.terminalEventId(), snapshot.agentState());
            return actual.equals(snapshot.checksum()) ? Optional.of(snapshot) : Optional.empty();
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    /** 根据身份、终态位置和规范化事件 JSON 计算稳定摘要。 */
    private static String checksum(
            String sessionId,
            String runId,
            long terminalRevision,
            String terminalEventId,
            AgentState agentState
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(sessionId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(runId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(terminalRevision).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(terminalEventId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(MAPPER.writeValueAsBytes(agentState));
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception failure) {
            throw new IllegalStateException("计算 RunSnapshot checksum 失败", failure);
        }
    }
}
