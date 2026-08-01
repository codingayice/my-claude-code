package cn.ayice.veyra.conversation.transcript;

import dev.langchain4j.data.message.ChatMessage;

/**
 * 运行时写 transcript 的端口。AgentLoop/ChatLoop 只知道“记录消息”，不直接知道 JSONL 文件路径。
 */
public interface TranscriptRecorder {

    /**
     * 将消息转换为 transcript 条目并追加到持久化存储。
     */
    void record(ChatMessage message);
}
