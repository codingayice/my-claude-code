package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.ToolResult;
import cn.ayice.veyra.tooling.state.FileStateCache;

import cn.ayice.veyra.tooling.permission.PermissionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileToolSchemaTest {

    @TempDir
    Path tempDir;

    @Test
    void readUsesClaudeStyleNameAndIgnoresExtraArguments() throws Exception {
        FileReadTool read = new FileReadTool(new FileStateCache());

        assertEquals("Read", read.name());
        assertEquals(Boolean.TRUE, read.getSpec().parameters().additionalProperties());

        Path sample = tempDir.resolve("sample.txt");
        Files.writeString(sample, "hello\n");

        ToolResult result = read.execute("{\"file_path\":\"sample.txt\",\"filePath\":\"ignored\"}", context());
        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("hello"));
    }

    @Test
    void editRequiresClaudeStyleArgumentsAndIgnoresExtraArguments() throws Exception {
        FileStateCache cache = new FileStateCache();
        FileReadTool read = new FileReadTool(cache);
        FileEditTool edit = new FileEditTool(cache);

        assertEquals(Boolean.TRUE, edit.getSpec().parameters().additionalProperties());

        Path sample = tempDir.resolve("sample.txt");
        Files.writeString(sample, "hello\n");

        PermissionContext context = context();
        assertTrue(read.execute("{\"file_path\":\"sample.txt\"}", context).success());

        ToolResult result = edit.execute(
                "{\"file_path\":\"sample.txt\",\"old_string\":\"hello\",\"new_string\":\"hi\",\"filePath\":\"ignored\",\"oldString\":\"ignored\"}",
                context
        );
        assertTrue(result.success(), result.content());
        assertEquals("hi\n", Files.readString(sample));
        assertEquals(List.of(sample.toAbsolutePath().normalize()), cache.recentModifiedPaths(5));
    }

    @Test
    void writeRequiresClaudeStyleArgumentsAndIgnoresExtraArguments() throws Exception {
        FileStateCache cache = new FileStateCache();
        FileWriteTool write = new FileWriteTool(cache);

        assertEquals("Write", write.name());
        assertEquals(Boolean.TRUE, write.getSpec().parameters().additionalProperties());

        Path sample = tempDir.resolve("written.txt");
        ToolResult result = write.execute(
                "{\"file_path\":\"written.txt\",\"content\":\"hello\\n\",\"filePath\":\"ignored\"}",
                context()
        );

        assertTrue(result.success(), result.content());
        assertEquals("hello\n", Files.readString(sample));
        assertEquals(List.of(sample.toAbsolutePath().normalize()), cache.recentModifiedPaths(5));
    }

    @Test
    void editRejectsStringBooleanForReplaceAll() {
        FileStateCache cache = new FileStateCache();
        FileEditTool edit = new FileEditTool(cache);

        ToolResult result = edit.execute(
                "{\"file_path\":\"sample.txt\",\"old_string\":\"hello\",\"new_string\":\"hi\",\"replace_all\":\"true\"}",
                context()
        );

        assertFalse(result.success());
        assertTrue(result.content().contains("replace_all 必须是布尔值"));
        assertTrue(cache.recentModifiedPaths(5).isEmpty());
    }

    @Test
    void editAllowsPartialReadWhenFileIsUnchanged() throws Exception {
        FileStateCache cache = new FileStateCache();
        FileReadTool read = new FileReadTool(cache);
        FileEditTool edit = new FileEditTool(cache);
        PermissionContext context = context();

        Path sample = tempDir.resolve("sample.txt");
        Files.writeString(sample, "hello\nworld\n");

        assertTrue(read.execute("{\"file_path\":\"sample.txt\",\"offset\":1,\"limit\":1}", context).success());

        ToolResult result = edit.execute(
                "{\"file_path\":\"sample.txt\",\"old_string\":\"hello\",\"new_string\":\"hi\"}",
                context
        );

        assertTrue(result.success(), result.content());
        assertEquals("hi\nworld\n", Files.readString(sample));
    }

    @Test
    void editRejectsPartialReadWhenFileWasModifiedAfterRead() throws Exception {
        FileStateCache cache = new FileStateCache();
        FileReadTool read = new FileReadTool(cache);
        FileEditTool edit = new FileEditTool(cache);
        PermissionContext context = context();

        Path sample = tempDir.resolve("sample.txt");
        Files.writeString(sample, "hello\nworld\n");

        assertTrue(read.execute("{\"file_path\":\"sample.txt\",\"offset\":1,\"limit\":1}", context).success());
        Files.writeString(sample, "hello\nworld\nchanged\n");
        Files.setLastModifiedTime(sample, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        ToolResult result = edit.execute(
                "{\"file_path\":\"sample.txt\",\"old_string\":\"hello\",\"new_string\":\"hi\"}",
                context
        );

        assertFalse(result.success());
        assertTrue(result.content().contains("文件在读取后已被用户或格式化工具修改"));
    }

    @Test
    void globReturnsNewestFilesFirst() throws Exception {
        GlobTool glob = new GlobTool();
        Path oldFile = tempDir.resolve("old.txt");
        Path newFile = tempDir.resolve("new.txt");
        Files.writeString(oldFile, "old\n");
        Files.writeString(newFile, "new\n");
        Files.setLastModifiedTime(oldFile, FileTime.fromMillis(1_000));
        Files.setLastModifiedTime(newFile, FileTime.fromMillis(2_000));

        ToolResult result = glob.execute(
                "{\"pattern\":\"*.txt\",\"path\":\"" + escape(tempDir) + "\"}",
                context()
        );

        assertTrue(result.success(), result.content());
        String[] lines = result.content().split("\\R");
        assertEquals("new.txt", lines[0]);
        assertEquals("old.txt", lines[1]);
    }

    private PermissionContext context() {
        return PermissionContext.builder()
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();
    }

    private String escape(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
