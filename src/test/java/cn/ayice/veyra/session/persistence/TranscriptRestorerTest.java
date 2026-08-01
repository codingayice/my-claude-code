package cn.ayice.veyra.session.persistence;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TranscriptRestorerTest {

    @Test
    void restoresSupportedTranscriptEntriesToChatHistory() {
        List<TranscriptEntry> entries = List.of(
                TranscriptEntry.user("session-1", "第一轮"),
                TranscriptEntry.assistant("session-1", "回答"),
                TranscriptEntry.toolResult("session-1", "tool-1", "FileRead", "文件内容")
        );

        List<ChatMessage> history = new TranscriptRestorer().restore(entries);

        assertEquals(3, history.size());
        assertInstanceOf(UserMessage.class, history.get(0));
        assertInstanceOf(AiMessage.class, history.get(1));
        assertInstanceOf(ToolExecutionResultMessage.class, history.get(2));
        assertEquals("user", history.get(0).type().name().toLowerCase());
        assertEquals("ai", history.get(1).type().name().toLowerCase());
        assertEquals("tool_execution_result", history.get(2).type().name().toLowerCase());
    }
}
