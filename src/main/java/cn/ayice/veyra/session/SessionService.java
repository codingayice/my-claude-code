package cn.ayice.veyra.session;

import cn.ayice.veyra.session.persistence.SessionRecord;
import cn.ayice.veyra.session.persistence.TranscriptEntry;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 会话能力的统一入口，集中提供活动会话、持久化记录和串行运行队列访问。
 * <p>运行编排只能通过该服务操作会话，不直接依赖 SessionRegistry 或持久化实现。</p>
 */
public class SessionService {

    private final SessionRegistry registry;

    /**
     * 使用会话注册表创建统一会话服务。
     */
    public SessionService(SessionRegistry registry) {
        this.registry = registry;
    }

    /**
     * 创建空历史会话并注册对应运行时。
     */
    public SessionRuntime create() {
        return registry.createSession();
    }

    /**
     * 返回活动会话；未激活时从持久化记录恢复。
     */
    public SessionRuntime getOrCreate(String sessionId) {
        return registry.getOrCreate(sessionId);
    }

    /**
     * 返回持久化会话摘要列表。
     */
    public List<SessionRecord> list() {
        return registry.listSessions();
    }

    /**
     * 返回指定会话的全部持久化转录条目。
     */
    public List<TranscriptEntry> transcript(String sessionId) {
        return registry.transcriptEntries(sessionId);
    }

    /**
     * 将一次运行加入指定会话的串行队列。
     */
    public CompletableFuture<Void> enqueue(String sessionId, Runnable run) {
        return getOrCreate(sessionId).enqueue(run);
    }
}
