package cn.ayice.veyra.tool.permission;

import cn.ayice.veyra.tool.builtin.BashTool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BashPermissionRulesTest {

    @TempDir
    Path workspace;

    @Test
    void contentAllowRuleAllowsMatchingNonReadOnlyBashCommand() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.ASK_EVERY_TIME)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .addRule(PermissionRule.builder()
                        .source("session")
                        .allow()
                        .tool("bash")
                        .content("npm run:*")
                        .build())
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new BashTool(false),
                request("npm run test"),
                context
        );

        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    @Test
    void readOnlyBashDenyCannotBeOverriddenByInheritedAllowRule() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.ASK_EVERY_TIME)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .addRule(PermissionRule.builder()
                        .source("session")
                        .allow()
                        .tool("bash")
                        .content("npm install:*")
                        .build())
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new BashTool(true),
                request("npm install left-pad"),
                context
        );

        assertEquals(PermissionDecision.Kind.DENY, decision.kind());
    }

    @Test
    void askEveryTimeModeAsksForWritableBash() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.ASK_EVERY_TIME)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new BashTool(false),
                request("npm install left-pad"),
                context
        );

        assertEquals(PermissionDecision.Kind.ASK, decision.kind());
    }

    @Test
    void autoApproveModeAllowsWritableBashAfterSelfCheck() {
        PermissionContext context = PermissionContext.builder()
                .mode(PermissionMode.AUTO_APPROVE)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .build();

        PermissionDecision decision = PermissionChecker.decide(
                new BashTool(false),
                request("npm install left-pad"),
                context
        );

        assertEquals(PermissionDecision.Kind.ALLOW, decision.kind());
    }

    private ToolExecutionRequest request(String command) {
        return ToolExecutionRequest.builder()
                .id("1")
                .name("bash")
                .arguments("{\"command\":\"" + command + "\"}")
                .build();
    }
}
