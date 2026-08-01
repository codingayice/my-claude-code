package cn.ayice.veyra.tooling.builtin;

import cn.ayice.veyra.tooling.ToolResult;

import cn.ayice.veyra.tooling.permission.PermissionContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrepToolTest {

    @TempDir
    Path tempDir;

    @Test
    void grepFindsFilesWithMatches() throws Exception {
        assumeRipgrepAvailable();

        Files.writeString(tempDir.resolve("a.txt"), "hello world\n");
        Files.writeString(tempDir.resolve("b.java"), "no match here\n");

        GrepTool grep = new GrepTool(java.util.concurrent.ForkJoinPool.commonPool());
        ToolResult result = grep.execute(
                "{\"pattern\":\"hello\",\"path\":\"" + escape(tempDir) + "\",\"output_mode\":\"files_with_matches\"}",
                context()
        );

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("a.txt"), result.content());
        assertFalse(result.content().contains("b.java"), result.content());
    }

    @Test
    void grepSupportsContentMode() throws Exception {
        assumeRipgrepAvailable();

        Files.writeString(tempDir.resolve("a.txt"), "hello world\n");

        GrepTool grep = new GrepTool(java.util.concurrent.ForkJoinPool.commonPool());
        ToolResult result = grep.execute(
                "{\"pattern\":\"hello\",\"path\":\"" + escape(tempDir) + "\",\"output_mode\":\"content\",\"-n\":true}",
                context()
        );

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("a.txt"), result.content());
        assertTrue(result.content().contains(":1:hello world"), result.content());
    }

    @Test
    void grepContentModeIncludesFilenameForSingleFilePath() throws Exception {
        assumeRipgrepAvailable();

        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world\n");

        GrepTool grep = new GrepTool(java.util.concurrent.ForkJoinPool.commonPool());
        ToolResult result = grep.execute(
                "{\"pattern\":\"hello\",\"path\":\"" + escape(file) + "\",\"output_mode\":\"content\",\"-n\":true}",
                context()
        );

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("a.txt:1:hello world"), result.content());
    }

    @Test
    void grepSupportsCountMode() throws Exception {
        assumeRipgrepAvailable();

        Files.writeString(tempDir.resolve("a.txt"), "hello world\nhello again\n");
        Files.writeString(tempDir.resolve("b.txt"), "hello there\n");

        GrepTool grep = new GrepTool(java.util.concurrent.ForkJoinPool.commonPool());
        ToolResult result = grep.execute(
                "{\"pattern\":\"hello\",\"path\":\"" + escape(tempDir) + "\",\"output_mode\":\"count\"}",
                context()
        );

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("共找到 3 处匹配，分布在 2 个文件中。"), result.content());
    }

    @Test
    void grepCountModeIncludesFilenameForSingleFilePath() throws Exception {
        assumeRipgrepAvailable();

        Path file = tempDir.resolve("a.txt");
        Files.writeString(file, "hello world\nhello again\n");

        GrepTool grep = new GrepTool(java.util.concurrent.ForkJoinPool.commonPool());
        ToolResult result = grep.execute(
                "{\"pattern\":\"hello\",\"path\":\"" + escape(file) + "\",\"output_mode\":\"count\"}",
                context()
        );

        assertTrue(result.success(), result.content());
        assertTrue(result.content().contains("a.txt:2"), result.content());
        assertTrue(result.content().contains("共找到 2 处匹配，分布在 1 个文件中。"), result.content());
    }

    @Test
    void grepRejectsInvalidOutputMode() {
        GrepTool grep = new GrepTool(java.util.concurrent.ForkJoinPool.commonPool());

        ToolResult result = grep.execute(
                "{\"pattern\":\"hello\",\"output_mode\":\"bad\"}",
                context()
        );

        assertFalse(result.success());
        assertTrue(result.content().contains("output_mode 必须是以下之一"), result.content());
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

    private void assumeRipgrepAvailable() {
        Assumptions.assumeTrue(isRipgrepAvailable(), "GrepTool 测试需要 rg");
    }

    private boolean isRipgrepAvailable() {
        try {
            Process process = new ProcessBuilder("rg", "--version").start();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
