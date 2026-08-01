package cn.ayice.veyra.context.instruction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectInstructionLoaderTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsInstructionFilesInStableOrderWithIncludes() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(home.resolve(".mycc"));
        Files.createDirectories(workspace.resolve(".claude"));
        Files.writeString(home.resolve(".mycc/CLAUDE.md"), "global rule\n");
        Files.writeString(workspace.resolve("included.md"), "included project detail\n");
        Files.writeString(workspace.resolve("CLAUDE.md"), "---\ntitle: ignored\n---\nproject rule\n@include included.md\n");
        Files.writeString(workspace.resolve(".claude/CLAUDE.md"), "dot claude rule\n");
        Files.writeString(workspace.resolve("CLAUDE.local.md"), "local rule\n<!-- secret comment -->\n");

        String loaded = new ProjectInstructionLoader(home, workspace).load();

        assertTrue(loaded.indexOf("global rule") < loaded.indexOf("project rule"));
        assertTrue(loaded.indexOf("project rule") < loaded.indexOf("dot claude rule"));
        assertTrue(loaded.indexOf("dot claude rule") < loaded.indexOf("local rule"));
        assertTrue(loaded.contains("included project detail"));
        assertFalse(loaded.contains("title: ignored"));
        assertFalse(loaded.contains("secret comment"));
    }

    @Test
    void loadsClaudeRulesWithPathFrontmatterAndBareAtIncludes() throws Exception {
        Path home = tempDir.resolve("home");
        Path workspace = tempDir.resolve("workspace");
        Files.createDirectories(home.resolve(".mycc"));
        Files.createDirectories(workspace.resolve(".claude/rules"));
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/App.java"), "class App {}\n");
        Files.writeString(workspace.resolve("shared.md"), "shared rule detail\n");
        Files.writeString(workspace.resolve(".claude/rules/01-java.md"), """
                ---
                paths:
                  - src/**/*.java
                ---
                java rule
                @shared.md
                """);
        Files.writeString(workspace.resolve(".claude/rules/02-python.md"), """
                ---
                paths:
                  - scripts/**/*.py
                ---
                python rule
                """);

        String loaded = new ProjectInstructionLoader(home, workspace).load();

        assertTrue(loaded.contains("java rule"));
        assertTrue(loaded.contains("shared rule detail"));
        assertFalse(loaded.contains("python rule"));
        assertFalse(loaded.contains("paths:"));
    }
}
