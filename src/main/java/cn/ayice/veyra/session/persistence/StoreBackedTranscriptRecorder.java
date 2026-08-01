package cn.ayice.veyra.session.persistence;

import dev.langchain4j.data.message.ChatMessage;

/**
 * 把运行时消息追加到当前 session 的 JSONL transcript。
 */
public class StoreBackedTranscriptRecorder implements TranscriptRecorder {

    private final String sessionId;
    private final TranscriptStore store;

    public StoreBackedTranscriptRecorder(String sessionId, TranscriptStore store) {
        this.sessionId = sessionId;
        this.store = store;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void record(ChatMessage message) {
        store.append(sessionId, TranscriptEntry.fromChatMessage(sessionId, message));
    }
}
