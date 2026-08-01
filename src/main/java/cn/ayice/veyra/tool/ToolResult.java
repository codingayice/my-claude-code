package cn.ayice.veyra.tool;

/**
 * 所有工具统一返回的执行结果。它携带成功标记和需要写回模型上下文的文本内容。
 */
public record ToolResult(boolean success, String content) {

    /**
     * 创建表示成功且携带结果数据的返回对象。
     */
    public static ToolResult success(String content) {
        return new ToolResult(true, content);
    }

    /**
     * 创建工具执行失败结果。
     */
    public static ToolResult error(String content) {
        return new ToolResult(false, "ERROR: " + content);
    }
}
