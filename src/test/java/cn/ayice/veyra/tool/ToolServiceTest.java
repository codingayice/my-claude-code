package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.subagent.AgentProfile.PermissionPolicy;
import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolService.Authorization;
import cn.ayice.veyra.tool.ToolService.Execution;
import cn.ayice.veyra.tool.state.FileStateCache;
import cn.ayice.veyra.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolServiceTest {

    @TempDir
    Path workspace;

    @Test
    void returnsApprovalRequiredWithoutBlocking() {
        PermissionContextStore store = new PermissionContextStore(context(PermissionMode.ASK_EVERY_TIME));
        ToolService engine = engine(new TestTool("bash", ToolResult.success("done")), store);
        List<String> lifecycle = new ArrayList<>();
        ToolExecutionRequest request = request("bash", "{\"command\":\"git status\"}");

        Authorization authorization = engine.authorize(
                request,
                store.current(),
                ToolExecutionPolicy.mainAgent(),
                observer(lifecycle)
        );
        assertTrue(authorization.approvalRequired());
        assertEquals(List.of("decided:ASK", "requested"), lifecycle);
        assertTrue(store.current().rules().isEmpty());
    }

    @Test
    void appliesSessionApprovalWhenPersistentDecisionIsResumed() {
        PermissionContextStore store = new PermissionContextStore(context(PermissionMode.ASK_EVERY_TIME));
        ToolService engine = engine(new TestTool("bash", ToolResult.success("done")), store);
        Authorization authorization = engine.resolve(
                request("bash", "{\"command\":\"git status\"}"), "allow_for_session", store.current());

        assertTrue(authorization.allowed());
        assertFalse(store.current().rules().isEmpty());
        assertEquals("done", engine.execute(authorization, authorization.context(),
                ToolExecutionPolicy.mainAgent()).content());
    }

    @Test
    void preservesMainAndSubagentEmptyResultMessages() {
        TestTool tool = new TestTool("Read", ToolResult.success(""));
        ToolExecutionRequest request = request("Read", "{}");
        PermissionContext context = context(PermissionMode.AUTO_APPROVE);
        ToolService engine = engine(tool, new PermissionContextStore(context));

        Authorization mainAuthorization = engine.authorize(
                request,
                context,
                ToolExecutionPolicy.mainAgent(),
                ToolExecutionObserver.NOOP
        );
        Authorization subagentAuthorization = engine.authorize(
                request,
                context,
                PermissionPolicy.general(),
                ToolExecutionObserver.NOOP
        );

        assertEquals(
                "<success>工具已成功执行，但结果为空</success>",
                engine.execute(mainAuthorization, context, ToolExecutionPolicy.mainAgent()).content()
        );
        assertEquals(
                "<success>工具已成功执行</success>",
                engine.execute(subagentAuthorization, context, PermissionPolicy.general()).content()
        );
    }

    @Test
    void returnsExistingRejectionTextWhenUserDeniesApproval() {
        ToolService engine = engine(
                new TestTool("Write", ToolResult.success("unused")),
                new PermissionContextStore(context(PermissionMode.ASK_EVERY_TIME))
        );

        Authorization authorization = engine.resolve(
                request("Write", "{}"), "deny", context(PermissionMode.ASK_EVERY_TIME));

        assertFalse(authorization.allowed());
        assertEquals("用户拒绝了工具调用", authorization.rejectionReason());
    }

    private ToolService engine(
            BaseTool tool,
            PermissionContextStore store
    ) {
        ToolCatalog catalog = ToolCatalog.create(List.of(tool), new FileStateCache());
        return new ToolService(catalog, store);
    }

    private PermissionContext context(PermissionMode mode) {
        return PermissionContext.builder()
                .mode(mode)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .build();
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder()
                .id("tool-use-1")
                .name(name)
                .arguments(arguments)
                .build();
    }

    private static ToolExecutionObserver observer(List<String> lifecycle) {
        return new ToolExecutionObserver() {
            @Override
            public void authorizationDecided(
                    ToolExecutionRequest request,
                    PermissionDecision decision
            ) {
                lifecycle.add("decided:" + decision.kind());
            }

            @Override
            public void permissionRequested(
                    ToolExecutionRequest request,
                    PermissionDecision decision
            ) {
                lifecycle.add("requested");
            }

        };
    }

    private static final class TestTool extends BaseTool {
        private final String name;
        private final ToolResult result;

        private TestTool(String name, ToolResult result) {
            this.name = name;
            this.result = result;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test tool";
        }

        @Override
        public Category category() {
            return Category.UTILITY;
        }

        @Override
        public Visibility visibility() {
            return Visibility.ALL;
        }

        @Override
        public RiskLevel riskLevel() {
            return RiskLevel.CAUTION;
        }

        @Override
        public PermissionDecision checkPermissions(String arguments, PermissionContext context) {
            return PermissionDecision.ask("test approval");
        }

        @Override
        public ToolResult execute(String arguments, PermissionContext context) {
            return result;
        }

        @Override
        public ToolSpecification getSpec() {
            return ToolSpecification.builder()
                    .name(name)
                    .description(description())
                    .build();
        }
    }
}
