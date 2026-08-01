package cn.ayice.veyra.context.prompt;


/**
 * 单个系统提示词片段的基类。每个子类只负责生成一段独立提示词，让整体提示词更容易维护。
 */
public abstract class SystemPromptSection {

    private final String name;
    private final boolean cacheBreak;

    protected SystemPromptSection(String name, boolean cacheBreak) {
        this.name = name;
        this.cacheBreak = cacheBreak;
    }

    /**
     * 返回当前组件的稳定名称。
     */
    public String name() {
        return name;
    }

    /**
     * 指示当前提示词片段后是否建立模型缓存边界。
     */
    public boolean cacheBreak() {
        return cacheBreak;
    }

    /**
     * 计算并返回当前系统提示词片段。
     */
    public abstract String compute(SystemPromptContext ctx);
}
