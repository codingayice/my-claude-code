package cn.ayice.veyra.tooling.permission;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionSupportTest {

    @TempDir
    Path tempDir;

    @Test
    void readPathAsksInsideProjectInAskEveryTimeMode() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.ASK_EVERY_TIME)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();

        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Read",
                tempDir.resolve("sample.txt"),
                context,
                "Read file: sample.txt"
        );

        assertEquals(PermissionDecision.Kind.ASK, decision.kind());
    }

    @Test
    void readPathAllowsInsideProjectInProjectAutoMode() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.PROJECT_AUTO)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();

        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Read",
                tempDir.resolve("sample.txt"),
                context,
                "Read file: sample.txt"
        );

        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void readPathAsksOutsideProjectInProjectAutoMode() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.PROJECT_AUTO)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();

        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Read",
                tempDir.getParent().resolve("outside.txt"),
                context,
                "Read file: outside.txt"
        );

        assertEquals(PermissionDecision.Kind.ASK, decision.kind());
    }

    @Test
    void readPathAllowsAutoApproveMode() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.AUTO_APPROVE)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();

        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Read",
                tempDir.getParent().resolve("outside.txt"),
                context,
                "Read file: outside.txt"
        );

        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void readPathAsksForUncPath() {
        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Read",
                Path.of("\\\\server\\share\\file.txt"),
                PermissionContext.builder().mode(PermissionMode.AUTO_APPROVE).build(),
                "Read file: \\\\server\\share\\file.txt"
        );

        assertEquals(PermissionDecision.Kind.ASK, decision.kind());
        assertTrue(decision.reason().contains("UNC path"));
    }

    @Test
    void readPathAsksForSuspiciousWindowsPath() {
        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Read",
                Path.of("C:\\repo\\GIT~1\\config"),
                PermissionContext.builder().mode(PermissionMode.AUTO_APPROVE).build(),
                "Read file: C:\\repo\\GIT~1\\config"
        );

        assertEquals(PermissionDecision.Kind.ASK, decision.kind());
        assertTrue(decision.reason().contains("suspicious Windows path"));
    }

    @Test
    void pathTraversalHelperDetectsParentSegments() {
        assertTrue(PermissionSupport.containsPathTraversal("src/../secret.txt"));
        assertTrue(PermissionSupport.containsPathTraversal("src\\..\\secret.txt"));
    }

    @Test
    void writePathAllowsAutoApproveMode() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.AUTO_APPROVE)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();

        PermissionDecision decision = PermissionSupport.checkWritePathPermission(
                "Edit",
                tempDir.getParent().resolve("outside.txt"),
                context,
                "Edit file: outside.txt"
        );

        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void writePathAllowsInsideProjectInProjectAutoMode() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.PROJECT_AUTO)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();

        PermissionDecision decision = PermissionSupport.checkWritePathPermission(
                "Edit",
                tempDir.resolve("sample.txt"),
                context,
                "Edit file: sample.txt"
        );

        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void writePathAsksInProjectAutoOutsideProject() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.PROJECT_AUTO)
                .workingDir(tempDir)
                .addAllowedDirectory(tempDir)
                .build();

        PermissionDecision decision = PermissionSupport.checkWritePathPermission(
                "Edit",
                tempDir.getParent().resolve("outside.txt"),
                context,
                "Edit file: outside.txt"
        );

        assertEquals(PermissionDecision.Kind.ASK, decision.kind());
    }
}
