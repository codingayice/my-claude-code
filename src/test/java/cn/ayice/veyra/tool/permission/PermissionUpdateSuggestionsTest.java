package cn.ayice.veyra.tool.permission;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PermissionUpdateSuggestionsTest {

    @TempDir
    Path workspace;

    @Test
    void sessionAllowForReadAddsMatchingDirectoryRule() {
        Path outsideFile = workspace.getParent().resolve("outside-read").resolve("secret.txt");
        PermissionContext context = baseContext();
        ToolExecutionRequest request = request("Read", "{\"file_path\":\"" + jsonPath(outsideFile) + "\"}");

        List<PermissionUpdate> updates = PermissionUpdateSuggestions.generateForSessionAllow(request, context);
        PermissionContext updated = PermissionUpdateApplier.apply(context, updates);
        PermissionRule rule = updated.findRule("Read", outsideFile.toString(), PermissionRule.PermissionBehavior.ALLOW);

        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Read",
                outsideFile,
                updated,
                "Read file: " + outsideFile
        );
        assertNotNull(rule);
        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void sessionAllowForGrepUsesSharedReadRule() {
        Path outsideDirectory = workspace.getParent().resolve("outside-grep");
        Path matchedFile = outsideDirectory.resolve("match.txt");
        PermissionContext context = baseContext();
        ToolExecutionRequest request = request("Grep", "{\"pattern\":\"TODO\",\"path\":\"" + jsonPath(outsideDirectory) + "\"}");

        List<PermissionUpdate> updates = PermissionUpdateSuggestions.generateForSessionAllow(request, context);
        PermissionContext updated = PermissionUpdateApplier.apply(context, updates);
        PermissionRule rule = updated.findRule("Read", matchedFile.toString(), PermissionRule.PermissionBehavior.ALLOW);

        PermissionDecision decision = PermissionSupport.checkReadPathPermission(
                "Grep",
                matchedFile,
                updated,
                "Search files: " + outsideDirectory
        );
        assertNotNull(rule);
        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void sessionAllowForWriteAddsExplicitAllowRuleAndParentDirectory() {
        Path outsideFile = workspace.getParent().resolve("outside-write").resolve("created.txt");
        PermissionContext context = baseContext();
        ToolExecutionRequest request = request("Write", "{\"file_path\":\"" + jsonPath(outsideFile) + "\",\"content\":\"hello\"}");

        List<PermissionUpdate> updates = PermissionUpdateSuggestions.generateForSessionAllow(request, context);
        PermissionContext updated = PermissionUpdateApplier.apply(context, updates);
        PermissionRule rule = updated.findRule("Write", outsideFile.toString(), PermissionRule.PermissionBehavior.ALLOW);

        PermissionDecision decision = PermissionSupport.checkWritePathPermission(
                "Write",
                outsideFile,
                updated,
                "Write file: " + outsideFile
        );
        assertNotNull(rule);
        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void sessionAllowForWriteKeepsAutoApproveMode() {
        Path outsideFile = workspace.getParent().resolve("outside-auto-write").resolve("created.txt");
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.AUTO_APPROVE)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .build();
        ToolExecutionRequest request = request("Write", "{\"file_path\":\"" + jsonPath(outsideFile) + "\",\"content\":\"hello\"}");

        List<PermissionUpdate> updates = PermissionUpdateSuggestions.generateForSessionAllow(request, context);
        PermissionContext updated = PermissionUpdateApplier.apply(context, updates);

        assertEquals(PermissionMode.AUTO_APPROVE, updated.mode());
        assertEquals(true, updated.isWithinAllowedDirectories(outsideFile));
    }

    @Test
    void sessionAllowForBashAddsPrefixRule() {
        PermissionContext context = baseContext();
        ToolExecutionRequest request = request("bash", "{\"command\":\"npm run build -- --watch\"}");

        List<PermissionUpdate> updates = PermissionUpdateSuggestions.generateForSessionAllow(request, context);
        PermissionContext updated = PermissionUpdateApplier.apply(context, updates);

        PermissionRule rule = updated.findRule("bash", "npm run test", PermissionRule.PermissionBehavior.ALLOW);
        assertEquals("npm run:*", rule.ruleContent());
    }

    private PermissionContext baseContext() {
        return PermissionContext.builder()
                .mode(PermissionMode.ASK_EVERY_TIME)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .build();
    }

    private ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("1")
                .name(name)
                .arguments(arguments)
                .build();
    }

    private String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }
}
