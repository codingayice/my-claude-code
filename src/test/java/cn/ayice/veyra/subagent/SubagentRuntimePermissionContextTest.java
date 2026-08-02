package cn.ayice.veyra.subagent;

import cn.ayice.veyra.subagent.AgentProfile.PermissionPolicy;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.tool.permission.PermissionRule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubagentRuntimePermissionContextTest {

    @TempDir
    Path workspace;

    @TempDir
    Path extraAllowedDirectory;

    @Test
    void subagentContextInheritsParentScopeAndRulesWhilePolicyMayOverrideMode() throws Exception {
        PermissionRule sessionAllow = PermissionRule.builder()
                .source("session")
                .allow()
                .tool("bash")
                .content("npm run:*")
                .build();
        PermissionContext parent = PermissionContext.builder()
                .mode(PermissionMode.AUTO_APPROVE)
                .workingDir(workspace)
                .addAllowedDirectory(workspace)
                .addAllowedDirectory(extraAllowedDirectory)
                .addRule(sessionAllow)
                .build();

        PermissionContext readOnlyContext = buildPermissionContext(parent, PermissionPolicy.readOnly());
        PermissionContext generalContext = buildPermissionContext(parent, PermissionPolicy.general());

        assertEquals(PermissionMode.ASK_EVERY_TIME, readOnlyContext.mode());
        assertEquals(PermissionMode.AUTO_APPROVE, generalContext.mode());
        assertEquals(parent.workingDir(), readOnlyContext.workingDir());
        assertTrue(readOnlyContext.allowedDirectories().contains(workspace.toAbsolutePath().normalize()));
        assertTrue(readOnlyContext.allowedDirectories().contains(extraAllowedDirectory.toAbsolutePath().normalize()));
        assertTrue(readOnlyContext.rules().contains(sessionAllow));
    }

    private PermissionContext buildPermissionContext(PermissionContext parent, PermissionPolicy policy) throws Exception {
        SubagentRuntime runtime = new SubagentRuntime(
                null,
                new AppConfig(null),
                null,
                null,
                null,
                SubagentToolCatalogs.factory(null, java.util.concurrent.ForkJoinPool.commonPool())
        );
        Method method = SubagentRuntime.class.getDeclaredMethod(
                "buildPermissionContext",
                PermissionContext.class,
                PermissionPolicy.class
        );
        method.setAccessible(true);
        return (PermissionContext) method.invoke(runtime, parent, policy);
    }
}
