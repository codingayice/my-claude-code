package cn.ayice.veyra.session;

import cn.ayice.veyra.session.persistence.SessionRecord;
import cn.ayice.veyra.session.persistence.SessionJournalEntry;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.event.AgentEvent;
import cn.ayice.veyra.session.recovery.SessionRecovery;

import java.util.List;

/**
 * Journal 查询入口，提供会话摘要、对话视图和稳定事件视图。
 */
public class SessionService {

    private final SessionJournalStore journalStore;

    public SessionService(SessionJournalStore journalStore) {
        this.journalStore = journalStore;
    }

    /**
     * 返回持久化会话摘要列表。
     */
    public List<SessionRecord> list() {
        return journalStore.listSessions();
    }

    /**
     * 返回指定会话的全部持久化转录条目。
     */
    public List<TranscriptItem> transcript(String sessionId) {
        return journalStore.read(sessionId).stream()
                .map(SessionService::toTranscriptItem)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /**
     * 返回可直接交给前端 reducer 的稳定会话事件；旧存储模式不提供该投影。
     */
    public List<AgentEvent> stableHistory(String sessionId) {
        return SessionRecovery.stableEvents(journalStore.read(sessionId));
    }

    /** 将 Journal 消息事实投影为只读对话条目。 */
    private static TranscriptItem toTranscriptItem(SessionJournalEntry entry) {
        return switch (entry.type()) {
            case SessionJournalTypes.USER_MESSAGE_RECORDED -> new TranscriptItem(
                    String.valueOf(entry.sequence()), entry.sessionId(), "user",
                    String.valueOf(entry.payload().getOrDefault("text", "")), null, null,
                    java.time.Instant.ofEpochMilli(entry.timestampMs()).toString()
            );
            case SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED -> new TranscriptItem(
                    String.valueOf(entry.sequence()), entry.sessionId(), "assistant",
                    String.valueOf(entry.payload().getOrDefault("text", "")), null, null,
                    java.time.Instant.ofEpochMilli(entry.timestampMs()).toString()
            );
            case SessionJournalTypes.TOOL_RESULT_RECORDED -> new TranscriptItem(
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
