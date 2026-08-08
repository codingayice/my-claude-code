package cn.ayice.veyra.session.persistence;

import dev.langchain4j.data.message.ChatMessage;

/**
 * 将模型协议消息记录为 Session Journal 稳定事实的运行时端口。
 */
public interface JournalMessageRecorder {

    /** 将一条模型消息追加为对应的 Journal 事件。 */
    void record(ChatMessage message);
}
