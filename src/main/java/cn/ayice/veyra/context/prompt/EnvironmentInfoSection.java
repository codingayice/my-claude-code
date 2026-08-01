package cn.ayice.veyra.context.prompt;

import java.nio.file.Path;

/**
 * 系统提示词中的运行环境片段。它把工作区、平台等模型不应自行猜测的信息注入上下文。
 */
public class EnvironmentInfoSection extends SystemPromptSection {

    public EnvironmentInfoSection() {
        super("environment_info", false);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String compute(SystemPromptContext ctx) {
        String os = System.getProperty("os.name", "unknown");
        String model = ctx.config().getModelName();
        Path workingDir = ctx.workingDir();

        return """
                工作目录 workingDir: %s
                路径规则:
                - 工具中的相对路径只基于 workingDir 解析
                - 如果用户要求操作 workingDir 之外的目录，必须使用绝对路径
                - 是否允许访问由权限系统判断，不要自行假设
                平台: %s
                模型: %s\
                """.formatted(workingDir, os, model);
    }
}
