package cn.ayice.veyra.tooling.permission;


import cn.ayice.veyra.tooling.permission.PermissionMode;
import cn.ayice.veyra.kernel.subagent.AgentProfile;
import cn.ayice.veyra.kernel.subagent.AgentProfiles;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentPermissionPolicyTest {

    @Test
    void readOnlyAgentsExposeOnlyReadAndReadOnlyBashPolicy() {
        AgentPermissionPolicy policy = AgentProfiles.fromType("Explore", 0).permissionPolicy();

        assertTrue(policy.allowsTool("Read"));
        assertTrue(policy.allowsTool("Glob"));
        assertTrue(policy.allowsTool("Grep"));
        assertTrue(policy.allowsTool("bash"));
        assertFalse(policy.allowsTool("Edit"));
        assertFalse(policy.allowsTool("Write"));
        assertTrue(policy.readOnlyBash());
        assertFalse(policy.canAskPermission());
        assertEquals(PermissionMode.ASK_EVERY_TIME, policy.permissionModeOverride());
    }

    @Test
    void generalAgentCanUseWriteToolsAndAskForPermission() {
        AgentPermissionPolicy policy = AgentProfiles.fromType("general-purpose", 0).permissionPolicy();

        assertTrue(policy.allowsTool("Read"));
        assertTrue(policy.allowsTool("Edit"));
        assertTrue(policy.allowsTool("Write"));
        assertTrue(policy.allowsTool("bash"));
        assertFalse(policy.readOnlyBash());
        assertTrue(policy.canAskPermission());
        assertNull(policy.permissionModeOverride());
    }

    @Test
    void memoryExtractionProfileUsesMemoryOnlyToolsAndPolicy() {
        AgentProfile profile = AgentProfiles.memoryExtraction(10);
        AgentPermissionPolicy policy = profile.permissionPolicy();

        assertEquals("memory-extraction", profile.type());
        assertEquals(5, profile.maxTurns());
        assertFalse(profile.recordTranscript());
        assertTrue(profile.toolProfile().allowedToolNames().contains("Memory"));
        assertFalse(profile.toolProfile().allowedToolNames().contains("Read"));
        assertFalse(profile.toolProfile().allowedToolNames().contains("Write"));
        assertFalse(profile.toolProfile().allowedToolNames().contains("Edit"));
        assertFalse(profile.toolProfile().allowedToolNames().contains("bash"));
        assertTrue(policy.allowsTool("Memory"));
        assertFalse(policy.allowsTool("Read"));
        assertFalse(policy.allowsTool("Write"));
        assertFalse(policy.allowsTool("Edit"));
        assertFalse(policy.allowsTool("bash"));
        assertFalse(policy.canAskPermission());
        assertEquals(PermissionMode.PROJECT_AUTO, policy.permissionModeOverride());
    }
}
