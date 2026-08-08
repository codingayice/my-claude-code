package cn.ayice.veyra.runtime.session;

import cn.ayice.veyra.session.persistence.SessionRecord;
import cn.ayice.veyra.session.persistence.TranscriptEntry;
import cn.ayice.veyra.session.persistence.TranscriptRestorer;
import cn.ayice.veyra.session.persistence.TranscriptStore;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.recovery.SessionRecovery;
import dev.langchain4j.data.message.ChatMessage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动会话运行时的注册表，也是 transcript 恢复为运行时对象的边界。
 */
public class RuntimeSessionRegistry implements AutoCloseable {

    private final TranscriptStore transcriptStore;
    private final TranscriptRestorer transcriptRestorer;
    private final Factory runtimeCreator;
    private final SessionJournalStore journalStore;
    private final SessionRecovery sessionRecovery;
    private final ConcurrentHashMap<String, SessionRuntime> sessions = new ConcurrentHashMap<>();

    /**
     * 使用转录存储、恢复器和运行时工厂创建会话注册表。
     */
    public RuntimeSessionRegistry(
            TranscriptStore transcriptStore,
            TranscriptRestorer transcriptRestorer,
            Factory runtimeCreator
    ) {
        this.transcriptStore = transcriptStore;
        this.transcriptRestorer = transcriptRestorer;
        this.runtimeCreator = runtimeCreator;
        this.journalStore = null;
        this.sessionRecovery = null;
    }

    /**
     * 使用 Durable Journal 和幂等恢复器创建生产注册表。
     */
    public RuntimeSessionRegistry(
            SessionJournalStore journalStore,
            SessionRecovery sessionRecovery,
            Factory runtimeCreator
    ) {
        this.transcriptStore = null;
        this.transcriptRestorer = null;
        this.runtimeCreator = runtimeCreator;
        this.journalStore = journalStore;
        this.sessionRecovery = sessionRecovery;
    }

    /**
     * 创建具有新会话标识和空历史的运行时。
     */
    public SessionRuntime createSession() {
        String sessionId = UUID.randomUUID().toString();
        SessionRuntime session = runtimeCreator.create(sessionId, List.of());
        session.persistCreation();
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
        return journalStore == null ? transcriptStore.listSessions() : journalStore.listSessions();
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
        if (sessionRecovery != null) {
            SessionRecovery.RecoveryResult recovery = sessionRecovery.recover(sessionId);
            SessionRuntime restored = runtimeCreator.create(sessionId, recovery);
            if (!recovery.persisted()) {
                restored.persistCreation();
            }
            return restored;
        }
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

    /**
     * Boot 层为指定会话标识和恢复历史装配会话运行时的契约。
     */
    @FunctionalInterface
    public interface Factory {
        /**
         * 创建一个会话独占运行时。
         */
        SessionRuntime create(String sessionId, List<ChatMessage> initialHistory);

        /**
         * Durable Journal 恢复入口；简单测试工厂可继续只实现旧签名。
         */
        default SessionRuntime create(String sessionId, SessionRecovery.RecoveryResult recovery) {
            return create(sessionId, recovery.agentHistory());
        }
    }
}
