package cn.ayice.veyra.host;

import dev.langchain4j.data.message.ChatMessage;

import java.util.List;

/**
 * Composition contract implemented by the boot package.
 */
@FunctionalInterface
public interface SessionRuntimeCreator {

    /**
     * 根据输入创建对应对象。
     */
    SessionRuntime create(String sessionId, List<ChatMessage> initialHistory);
}
