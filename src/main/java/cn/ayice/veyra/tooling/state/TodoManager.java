package cn.ayice.veyra.tooling.state;

import cn.ayice.veyra.tooling.ToolResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 主 agent 的 todo 状态管理器。它保存 todo 项、格式化给模型，并向前端发送更新事件。
 */
public class TodoManager {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Todo 列表中的内容、状态和进行时描述。
     */
    public record TodoItem(String content, String status, String activeForm) {
        public TodoItem {
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("content 不能为空");
            }
            if (status == null || (!"pending".equals(status) && !"in_progress".equals(status) && !"completed".equals(status))) {
                throw new IllegalArgumentException("status 必须是 pending / in_progress / completed");
            }
        }

        /**
         * 根据输入创建对应对象。
         */
        public static TodoItem fromJson(JsonNode node) {
            return new TodoItem(
                    node.path("content").asText(),
                    node.path("status").asText("pending"),
                    node.path("activeForm").asText(null)
            );
        }

        /**
         * 返回 Todo 状态对应的稳定文本标签。
         */
        public String statusLabel() {
            return switch (status) {
                case "completed" -> "已完成";
                case "in_progress" -> "进行中";
                default -> "待处理";
            };
        }

        /**
         * 返回 Todo 状态对应的终端显示符号。
         */
        public String statusIcon() {
            return switch (status) {
                case "completed" -> "✓";
                case "in_progress" -> "►";
                default -> "○";
            };
        }
    }

    private final List<TodoItem> items = new ArrayList<>();
    private BiConsumer<String, Map<String, Object>> eventSink;

    /**
     * 注入状态管理所需的执行器和事件出口。
     */
    public TodoManager() {
    }

    /**
     * 注入状态管理所需的执行器和事件出口。
     */
    public TodoManager(BiConsumer<String, Map<String, Object>> eventSink) {
        this.eventSink = eventSink;
    }

    /**
     * 替换 Todo 状态变化使用的事件出口；null 会退回空实现。
     */
    public void setEventSink(BiConsumer<String, Map<String, Object>> eventSink) {
        this.eventSink = eventSink;
    }

    /**
     * 全量替换更新 todo 列表。claude-code 风格：每次传入完整数组。
     * 当所有项 status 为 completed 时自动清空。
     */
    public synchronized String update(String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            JsonNode itemNode = args.path("todos");
            if (!itemNode.isArray()) {
                return ToolResult.error("todos 必须是数组").content();
            }

            items.clear();
            for (JsonNode node : itemNode) {
                try {
                    items.add(TodoItem.fromJson(node));
                } catch (IllegalArgumentException e) {
                    items.clear();
                    return ToolResult.error("无效的 todo 项: " + e.getMessage()).content();
                }
            }

            // 全部完成则自动清空
            if (!items.isEmpty() && items.stream().allMatch(item -> "completed".equals(item.status()))) {
                items.clear();
            }

            // 推送事件到前端
            if (eventSink != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("items", toItemMaps());
                eventSink.accept("todo.updated", payload);
            }

            return ToolResult.success(formatForAI()).content();
        } catch (Exception e) {
            return ToolResult.error(e.getMessage()).content();
        }
    }

    /**
     * 格式化给 AI 看的摘要
     */
    public synchronized String formatForAI() {
        if (items.isEmpty()) {
            return "Todo 列表为空。所有任务已完成。";
        }
        StringBuilder sb = new StringBuilder();
        for (TodoItem item : items) {
            sb.append(item.statusIcon()).append(" ").append(item.content());
            if (item.activeForm() != null && !item.activeForm().isBlank()) {
                sb.append("（").append(item.activeForm()).append("）");
            }
            sb.append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 判断 Todo 列表中是否仍有待处理或进行中的条目。
     */
    public synchronized boolean hasOpenItems() {
        return items.stream().anyMatch(item -> !"completed".equals(item.status()));
    }

    /**
     * 返回条目。
     */
    public synchronized List<TodoItem> getItems() {
        return new ArrayList<>(items);
    }

    /**
     * 转为前端可消费的 Map 列表
     */
    public synchronized List<Map<String, String>> toItemMaps() {
        List<Map<String, String>> list = new ArrayList<>();
        for (TodoItem item : items) {
            Map<String, String> map = new LinkedHashMap<>();
            map.put("content", item.content());
            map.put("status", item.status());
            map.put("activeForm", item.activeForm() == null ? "" : item.activeForm());
            list.add(map);
        }
        return list;
    }
}
