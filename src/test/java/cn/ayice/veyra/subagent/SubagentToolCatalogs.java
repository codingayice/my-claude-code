package cn.ayice.veyra.subagent;

import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.memory.tool.MemoryTool;
import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.tool.builtin.BashTool;
import cn.ayice.veyra.tool.builtin.FileEditTool;
import cn.ayice.veyra.tool.builtin.FileReadTool;
import cn.ayice.veyra.tool.builtin.FileWriteTool;
import cn.ayice.veyra.tool.builtin.GlobTool;
import cn.ayice.veyra.tool.builtin.GrepTool;
import cn.ayice.veyra.subagent.AgentProfile.PermissionPolicy;
import cn.ayice.veyra.tool.state.FileStateCache;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

public final class SubagentToolCatalogs {

    private SubagentToolCatalogs() {
    }

    public static SubagentToolCatalogFactory factory(MemoryService memoryService, Executor ioExecutor) {
        return profile -> create(profile, memoryService, ioExecutor);
    }

    private static ToolCatalog create(AgentProfile profile, MemoryService memoryService, Executor ioExecutor) {
        FileStateCache fileStateCache = new FileStateCache();
        PermissionPolicy policy = profile.permissionPolicy();
        List<BaseTool> tools = new ArrayList<>();
        tools.add(new FileReadTool(fileStateCache));
        tools.add(new FileEditTool(fileStateCache));
        tools.add(new FileWriteTool(fileStateCache));
        tools.add(new GlobTool());
        tools.add(new GrepTool(ioExecutor));
        tools.add(new BashTool(policy.readOnlyBash()));
        if (memoryService != null) {
            tools.add(new MemoryTool(memoryService));
        }
        return ToolCatalog.create(tools, fileStateCache).profile(profile.toolProfile());
    }
}
