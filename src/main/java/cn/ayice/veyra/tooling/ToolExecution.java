package cn.ayice.veyra.tooling;

import cn.ayice.veyra.tooling.ToolResult;
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
