package cn.ayice.veyra.session;

import cn.ayice.veyra.session.persistence.SessionRecord;
import cn.ayice.veyra.session.persistence.TranscriptEntry;
import cn.ayice.veyra.session.persistence.TranscriptStore;

import java.util.List;

/**
 * 持久化会话查询入口，只负责会话摘要和 transcript 读取。
 */
public class SessionService {

    private final TranscriptStore transcriptStore;

    /**
     * 使用唯一 transcript 存储创建会话查询服务。
     */
    public SessionService(TranscriptStore transcriptStore) {
        this.transcriptStore = transcriptStore;
    }

    /**
     * 返回持久化会话摘要列表。
     */
    public List<SessionRecord> list() {
        return transcriptStore.listSessions();
    }

    /**
     * 返回指定会话的全部持久化转录条目。
     */
    public List<TranscriptEntry> transcript(String sessionId) {
        return transcriptStore.read(sessionId);
    }
}
