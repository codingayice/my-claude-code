package cn.ayice.veyra.host;

import cn.ayice.veyra.conversation.transcript.SessionRecord;
import cn.ayice.veyra.conversation.transcript.TranscriptEntry;
import cn.ayice.veyra.conversation.transcript.TranscriptRestorer;
import cn.ayice.veyra.conversation.transcript.TranscriptStore;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动会话运行时的注册表，也是 transcript 恢复为运行时对象的边界。
 */
public class SessionRegistry implements AutoCloseable {

    private final TranscriptStore transcriptStore;
    private final TranscriptRestorer transcriptRestorer;
    private final SessionRuntimeCreator runtimeCreator;
    private final ConcurrentHashMap<String, SessionRuntime> sessions = new ConcurrentHashMap<>();

    /**
     * 使用转录存储、恢复器和运行时工厂创建会话注册表。
     */
    public SessionRegistry(
            TranscriptStore transcriptStore,
            TranscriptRestorer transcriptRestorer,
            SessionRuntimeCreator runtimeCreator
    ) {
        this.transcriptStore = transcriptStore;
        this.transcriptRestorer = transcriptRestorer;
        this.runtimeCreator = runtimeCreator;
    }

    /**
     * 创建具有新会话标识和空历史的运行时。
     */
    public SessionRuntime createSession() {
        String sessionId = UUID.randomUUID().toString();
        SessionRuntime session = runtimeCreator.create(sessionId, List.of());
        sessions.put(sessionId, session);
        return session;
    }

    /**
     * 返回活动会话；未激活时原子地从 transcript 恢复并注册。
     */
    public SessionRuntime getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, this::restoreSession);
    }

    /**
     * 返回已激活的会话，不触发恢复。
     */
    public SessionRuntime get(String sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * 返回存储中已有的会话摘要。
     */
    List<SessionRecord> listSessions() {
        return transcriptStore.listSessions();
    }

    /**
     * 读取指定会话的全部 transcript 条目。
     */
    List<TranscriptEntry> transcriptEntries(String sessionId) {
        return transcriptStore.read(sessionId);
    }

    /**
     * 将持久化 transcript 转换为模型历史并创建新的活动运行时。
     */
    private SessionRuntime restoreSession(String sessionId) {
        List<TranscriptEntry> entries = transcriptStore.read(sessionId);
        return runtimeCreator.create(sessionId, transcriptRestorer.restore(entries));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        sessions.values().forEach(SessionRuntime::close);
        sessions.clear();
    }
}
