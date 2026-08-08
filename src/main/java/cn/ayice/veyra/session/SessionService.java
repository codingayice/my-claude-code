package cn.ayice.veyra.session;

import cn.ayice.veyra.session.persistence.SessionRecord;
import cn.ayice.veyra.session.persistence.TranscriptEntry;
import cn.ayice.veyra.session.persistence.TranscriptStore;
import cn.ayice.veyra.session.persistence.SessionJournalEntry;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.event.AgentEvent;
import cn.ayice.veyra.session.recovery.SessionRecovery;

import java.util.List;

/**
 * 持久化会话查询入口，只负责会话摘要和 transcript 读取。
 */
public class SessionService {

    private final TranscriptStore transcriptStore;
    private final SessionJournalStore journalStore;

    /**
     * 使用唯一 transcript 存储创建会话查询服务。
     */
    public SessionService(TranscriptStore transcriptStore) {
        this.transcriptStore = transcriptStore;
        this.journalStore = null;
    }

    public SessionService(SessionJournalStore journalStore) {
        this.transcriptStore = null;
        this.journalStore = journalStore;
    }

    /**
     * 返回持久化会话摘要列表。
     */
    public List<SessionRecord> list() {
        return journalStore == null ? transcriptStore.listSessions() : journalStore.listSessions();
    }

    /**
     * 返回指定会话的全部持久化转录条目。
     */
    public List<TranscriptEntry> transcript(String sessionId) {
        if (journalStore == null) {
            return transcriptStore.read(sessionId);
        }
        return journalStore.read(sessionId).stream()
                .map(SessionService::toTranscriptEntry)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 返回可直接交给前端 reducer 的稳定会话事件；旧存储模式不提供该投影。
     */
    public List<AgentEvent> stableHistory(String sessionId) {
        if (journalStore == null) {
            return List.of();
        }
        return SessionRecovery.stableEvents(journalStore.read(sessionId));
    }

    /** 将新 Journal 消息事实适配为现有控制面 Transcript DTO。 */
    private static TranscriptEntry toTranscriptEntry(SessionJournalEntry entry) {
        return switch (entry.type()) {
            case SessionJournalTypes.USER_MESSAGE_RECORDED -> new TranscriptEntry(
                    String.valueOf(entry.sequence()), entry.sessionId(), "user",
                    String.valueOf(entry.payload().getOrDefault("text", "")), null, null,
                    java.time.Instant.ofEpochMilli(entry.timestampMs()).toString()
            );
            case SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED -> new TranscriptEntry(
                    String.valueOf(entry.sequence()), entry.sessionId(), "assistant",
                    String.valueOf(entry.payload().getOrDefault("text", "")), null, null,
                    java.time.Instant.ofEpochMilli(entry.timestampMs()).toString()
            );
            case SessionJournalTypes.TOOL_RESULT_RECORDED -> new TranscriptEntry(
                    String.valueOf(entry.sequence()), entry.sessionId(), "tool_result",
                    String.valueOf(entry.payload().getOrDefault("content", "")),
                    String.valueOf(entry.payload().getOrDefault("toolUseId", "")),
                    String.valueOf(entry.payload().getOrDefault("name", "")),
                    java.time.Instant.ofEpochMilli(entry.timestampMs()).toString()
            );
            default -> null;
        };
    }
}
