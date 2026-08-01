package cn.ayice.veyra.session.persistence;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TranscriptStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesTranscriptPathUnderWorkspaceProjectBucket() {
        SessionPathResolver resolver = new SessionPathResolver(tempDir.toString(), "D:\\acm\\Java\\my-claude-code");

        Path path = resolver.transcriptPath("session-1");

        assertEquals(tempDir.resolve("projects").resolve("D--acm-Java-my-claude-code").resolve("session-1.jsonl"), path);
    }

    @Test
    void appendsAndReadsTranscriptEntriesInOrder() throws Exception {
        TranscriptStore store = new TranscriptStore(new SessionPathResolver(tempDir.toString(), "D:\\workspace"));

        store.append("session-1", TranscriptEntry.fromChatMessage("session-1", UserMessage.from("你好")));
        store.append("session-1", TranscriptEntry.fromChatMessage("session-1", AiMessage.from("收到")));

        Path transcript = tempDir.resolve("projects").resolve("D--workspace").resolve("session-1.jsonl");
        assertTrue(Files.exists(transcript));
        assertEquals(2, Files.readAllLines(transcript).size());

        List<TranscriptEntry> entries = store.read("session-1");
        assertEquals("user", entries.get(0).role());
        assertEquals("你好", entries.get(0).content());
        assertEquals("assistant", entries.get(1).role());
        assertEquals("收到", entries.get(1).content());
    }

    @Test
    void listsSessionsByNewestTranscriptFirst() {
        TranscriptStore store = new TranscriptStore(new SessionPathResolver(tempDir.toString(), "D:\\workspace"));

        store.append("older", TranscriptEntry.user("older", "旧会话"));
        store.append("newer", TranscriptEntry.user("newer", "新会话"));

        List<SessionRecord> records = store.listSessions();

        assertEquals(List.of("newer", "older"), records.stream().map(SessionRecord::sessionId).toList());
        assertEquals("新会话", records.get(0).title());
    }
}
