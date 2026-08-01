package cn.ayice.veyra.tool;

import cn.ayice.veyra.tool.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

/**
 * Normalized tool execution result ready to append to model history.
 */
public record ToolExecution(
        ToolExecutionRequest request,
        ToolResult result,
        String content
) {
}
