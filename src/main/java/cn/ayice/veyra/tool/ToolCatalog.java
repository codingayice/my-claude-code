package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.state.FileStateCache;

import java.util.List;

/**
 * 同一工具集合的模型可见目录、执行分发器和文件状态缓存。
 * <p>目录通过单次工具列表同时构造 Registry 与 Dispatcher，防止可见工具和可执行工具分叉。</p>
 */
public final class ToolCatalog {

    private final ToolRegistry registry;
    private final ToolDispatcher dispatcher;
    private final FileStateCache fileStateCache;

    private ToolCatalog(
            ToolRegistry registry,
            ToolDispatcher dispatcher,
            FileStateCache fileStateCache
    ) {
        this.registry = registry;
        this.dispatcher = dispatcher;
        this.fileStateCache = fileStateCache;
    }

    /**
     * 使用同一组工具同时创建模型目录和执行分发器。
     */
    public static ToolCatalog create(List<? extends BaseTool> tools, FileStateCache fileStateCache) {
        ToolRegistry registry = new ToolRegistry();
        ToolDispatcher dispatcher = new ToolDispatcher();
        for (BaseTool tool : tools) {
            registry.register(tool);
            dispatcher.register(tool);
        }
        return new ToolCatalog(registry, dispatcher, fileStateCache);
    }

    /**
     * 返回只保留指定 Profile 可见工具的目录视图。
     */
    public ToolCatalog profile(ToolRegistry.ToolProfile profile) {
        return new ToolCatalog(
                registry.profile(profile),
                dispatcher.profile(profile),
                fileStateCache
        );
    }

    /**
     * 返回模型可见工具注册表。
     */
    public ToolRegistry registry() {
        return registry;
    }

    /**
     * 返回实际可执行工具分发器。
     */
    public ToolDispatcher dispatcher() {
        return dispatcher;
    }

    /**
     * 返回该工具集合独占的文件状态缓存。
     */
    public FileStateCache fileStateCache() {
        return fileStateCache;
    }
}
