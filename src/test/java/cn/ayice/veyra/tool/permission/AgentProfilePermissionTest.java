package cn.ayice.veyra.tool.permission;


import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.subagent.AgentProfile;
import cn.ayice.veyra.subagent.AgentProfile.PermissionPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentProfilePermissionTest {

    @Test
    void readOnlyAgentsExposeOnlyReadAndReadOnlyBashPolicy() {
        PermissionPolicy policy = AgentProfile.fromType("Explore", 0).permissionPolicy();

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
        PermissionPolicy policy = AgentProfile.fromType("general-purpose", 0).permissionPolicy();

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
        AgentProfile profile = AgentProfile.memoryExtraction(10);
        PermissionPolicy policy = profile.permissionPolicy();

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
