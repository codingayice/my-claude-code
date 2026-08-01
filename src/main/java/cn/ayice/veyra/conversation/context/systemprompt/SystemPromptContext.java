package cn.ayice.veyra.conversation.context.systemprompt;


import cn.ayice.veyra.config.AppConfig;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 构建系统提示词时传递的只读上下文。各个 section 通过它访问配置、工具元数据和当前工作区。
 */
public record SystemPromptContext(
        AppConfig config,
        List<ToolSpecification> toolSpecifications,
        Map<String, String> toolDescriptions,
        Path workingDir
) {
}
