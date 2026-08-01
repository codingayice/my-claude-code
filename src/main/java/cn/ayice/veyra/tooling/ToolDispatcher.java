package cn.ayice.veyra.tooling;

import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolRegistry;
import cn.ayice.veyra.tooling.ToolResult;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 工具执行分发边界。它按稳定名称定位工具，并把实现异常归一化为可返回的失败结果。
 */
public class ToolDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ToolDispatcher.class);

    private final Map<String, BaseTool> toolMap = new HashMap<>();

    /**
     * 按稳定工具名注册可执行实例。
     */
    public void register(BaseTool tool) {
        toolMap.put(tool.name(), tool);
    }

    /**
     * 返回指定名称的工具，未注册时返回空值。
     */
    public BaseTool getTool(String name) {
        return toolMap.get(name);
    }

    /**
     * 在无权限上下文场景执行模型工具请求。
     */
    public ToolResult execute(ToolExecutionRequest req) {
        return execute(req, null);
    }

    /**
     * 按请求中的稳定名称执行工具，并把未知工具和实现异常归一化为失败结果。
     */
    public ToolResult execute(ToolExecutionRequest req, PermissionContext context) {
        BaseTool tool = toolMap.get(req.name());
        if (tool == null) {
            return ToolResult.error("未找到工具[" + req.name() + "]");
        }
        try {
            // 只有调用方提供上下文时才进入工具的上下文重载，保持旧工具执行契约兼容。
            ToolResult result;
            if (context != null) {
                result = tool.execute(req.arguments(), context);
            } else {
                result = tool.execute(req.arguments());
            }
            if (!result.success()) {
                log.warn("tool returned failure toolUseId={} tool={} content={}",
                        req.id(), req.name(), abbreviate(result.content()));
            }
            return result;
        } catch (Exception e) {
            // Dispatcher 是工具实现异常的最后边界；此处记录完整堆栈并返回模型可消费的失败结果。
            log.error("tool execution failed toolUseId={} tool={}", req.id(), req.name(), e);
            return ToolResult.error("工具执行失败，原因是 " + e.getMessage());
        }
    }

    /**
     * 截断日志中的工具失败内容，避免大结果污染日志。
     */
    private static String abbreviate(String content) {
        if (content == null || content.length() <= 500) {
            return content;
        }
        return content.substring(0, 500) + "...";
    }

    /**
     * 创建只包含指定 profile 可见工具的独立分发器。
     */
    public ToolDispatcher profile(ToolRegistry.ToolProfile profile) {
        ToolDispatcher sub = new ToolDispatcher();
        toolMap.forEach((k, v) -> {
            if (ToolRegistry.matches(profile, k, v)) {
                sub.register(v);
            }
        });
        return sub;
    }
}
