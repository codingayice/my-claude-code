package cn.ayice.veyra.tooling.permission;

import cn.ayice.veyra.tooling.builtin.GrepTool;
import cn.ayice.veyra.tooling.builtin.FileEditTool;
import cn.ayice.veyra.tooling.state.FileStateCache;
import cn.ayice.veyra.tooling.builtin.FileWriteTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionCheckerValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void invalidInputIsDeniedBeforeToolPermissionCheck() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("Grep")
                .arguments("{\"path\":\".\"}")
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new GrepTool(java.util.concurrent.ForkJoinPool.commonPool()),
                request,
                context()
        );

        assertEquals(PermissionDecision.Kind.DENY, decision.kind());
        assertTrue(decision.reason().contains("pattern 是必填字段"));
    }

    @Test
    void invalidEditInputIsDeniedBeforeToolPermissionCheck() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("Edit")
                .arguments("{\"file_path\":\"sample.txt\",\"old_string\":\"hello\"}")
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new FileEditTool(new FileStateCache()),
                request,
                context()
        );

        assertEquals(PermissionDecision.Kind.DENY, decision.kind());
        assertTrue(decision.reason().contains("new_string 是必填字段"));
    }

    @Test
    void invalidWriteInputIsDeniedBeforeToolPermissionCheck() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("Write")
                .arguments("{\"file_path\":\"sample.txt\",\"content\":123}")
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new FileWriteTool(new FileStateCache()),
                request,
                context()
        );

        assertEquals(PermissionDecision.Kind.DENY, decision.kind());
        assertTrue(decision.reason().contains("content 必须是字符串"));
    }

    @Test
    void validInputContinuesToToolPermissionCheck() {
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("1")
                .name("Grep")
                .arguments("{\"pattern\":\"hello\",\"path\":\".\"}")
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new GrepTool(java.util.concurrent.ForkJoinPool.commonPool()),
                request,
                context()
        );

        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    private PermissionContext context() {
        return PermissionContext.builder()
                .mode(PermissionMode.PROJECT_AUTO)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();
    }
}
