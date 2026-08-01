package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionDecision;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.tool.permission.AgentPermissionPolicy;
import cn.ayice.veyra.tool.ToolExecutionConfirmation;
import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolDispatcher;
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
    void appliesSessionApprovalBeforeExecutingTool() {
        PermissionContextStore store = new PermissionContextStore(context(PermissionMode.ASK_EVERY_TIME));
        RecordingConfirmation confirmation = new RecordingConfirmation(
                ToolExecutionConfirmation.Choice.ALLOW_FOR_SESSION
        );
        ToolService engine = engine(new TestTool("bash", ToolResult.success("done")), confirmation, store);
        List<String> lifecycle = new ArrayList<>();
        ToolExecutionRequest request = request("bash", "{\"command\":\"git status\"}");

        ToolAuthorization authorization = engine.authorize(
                request,
                store.current(),
                ToolExecutionPolicy.mainAgent(),
                observer(lifecycle)
        );
        ToolExecution execution = engine.execute(
                authorization,
                authorization.context(),
                ToolExecutionPolicy.mainAgent()
        );

        assertTrue(authorization.allowed());
        assertEquals(List.of("decided:ASK", "requested", "resolved:ALLOW_FOR_SESSION"), lifecycle);
        assertEquals(1, confirmation.calls);
        assertFalse(store.current().rules().isEmpty());
        assertEquals("done", execution.content());
    }

    @Test
    void preservesMainAndSubagentEmptyResultMessages() {
        TestTool tool = new TestTool("Read", ToolResult.success(""));
        ToolExecutionRequest request = request("Read", "{}");
        PermissionContext context = context(PermissionMode.AUTO_APPROVE);
        ToolService engine = engine(tool, null, new PermissionContextStore(context));

        ToolAuthorization mainAuthorization = engine.authorize(
                request,
                context,
                ToolExecutionPolicy.mainAgent(),
                ToolExecutionObserver.NOOP
        );
        ToolAuthorization subagentAuthorization = engine.authorize(
                request,
                context,
                AgentPermissionPolicy.general(),
                ToolExecutionObserver.NOOP
        );

        assertEquals(
                "<success>工具已成功执行，但结果为空</success>",
                engine.execute(mainAuthorization, context, ToolExecutionPolicy.mainAgent()).content()
        );
        assertEquals(
                "<success>工具已成功执行</success>",
                engine.execute(subagentAuthorization, context, AgentPermissionPolicy.general()).content()
        );
    }

    @Test
    void returnsExistingRejectionTextWhenUserDeniesApproval() {
        ToolService engine = engine(
                new TestTool("Write", ToolResult.success("unused")),
                new RecordingConfirmation(ToolExecutionConfirmation.Choice.DENY),
                new PermissionContextStore(context(PermissionMode.ASK_EVERY_TIME))
        );

        ToolAuthorization authorization = engine.authorize(
                request("Write", "{}"),
                context(PermissionMode.ASK_EVERY_TIME),
                ToolExecutionPolicy.mainAgent(),
                ToolExecutionObserver.NOOP
        );

        assertFalse(authorization.allowed());
        assertEquals("用户拒绝了工具调用", authorization.rejectionReason());
    }

    private ToolService engine(
            BaseTool tool,
            ToolExecutionConfirmation confirmation,
            PermissionContextStore store
    ) {
        ToolDispatcher dispatcher = new ToolDispatcher();
        dispatcher.register(tool);
        return new ToolService(dispatcher, confirmation, store);
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

            @Override
            public void permissionResolved(
                    ToolExecutionRequest request,
                    ToolExecutionConfirmation.Choice choice
            ) {
                lifecycle.add("resolved:" + choice);
            }
        };
    }

    private static final class RecordingConfirmation extends ToolExecutionConfirmation {
        private final Choice choice;
        private int calls;

        private RecordingConfirmation(Choice choice) {
            this.choice = choice;
        }

        @Override
        public Choice ask(ToolExecutionRequest request, String reason) {
            calls++;
            return choice;
        }
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
