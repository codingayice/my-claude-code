package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.WorkingMessage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 第一级上下文压缩器，只截断或清理可重新获取的旧工具结果，不调用模型。
 */
public final class MicroCompactor {

    static final int TRIM_THRESHOLD_CHARS = 2_000;
    static final int TRIM_HEAD_CHARS = 250;
    static final int TRIM_TAIL_CHARS = 250;
    static final int KEEP_RECENT_RESULTS = 5;
    static final int CLEAR_INTERVAL_ROUNDS = 50;
    static final String CLEARED_RESULT = "[旧工具结果已清除]";
    static final String TRUNCATION_MARKER = "\n...[工具结果过长，已截断，中间内容已省略]...\n";

    private static final Set<String> COMPACTABLE_TOOLS = Set.of(
            "Read", "Bash", "Grep", "Glob", "Edit", "Write"
    );

    /**
     * 按当前主 Agent 模型回合执行字符截断或每 50 回合旧结果清理。
     */
    public CompactionService.Result compact(List<WorkingMessage> messages, long completedModelRounds) {
        List<String> compactableIds = new ArrayList<>();
        for (WorkingMessage workingMessage : messages) {
            if (workingMessage.message() instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                aiMessage.toolExecutionRequests().stream()
                        .filter(request -> COMPACTABLE_TOOLS.contains(request.name()))
                        .forEach(request -> compactableIds.add(request.id()));
            }
        }
        Set<String> recentIds = compactableIds.size() <= KEEP_RECENT_RESULTS
                ? Set.copyOf(compactableIds)
                : Set.copyOf(compactableIds.subList(compactableIds.size() - KEEP_RECENT_RESULTS, compactableIds.size()));
        boolean clearOldResults = completedModelRounds > 0
                && completedModelRounds % CLEAR_INTERVAL_ROUNDS == 0;
        boolean changed = false;
        List<WorkingMessage> result = new ArrayList<>(messages.size());
        for (WorkingMessage workingMessage : messages) {
            if (!(workingMessage.message() instanceof ToolExecutionResultMessage toolResult)
                    || !COMPACTABLE_TOOLS.contains(toolResult.toolName())
                    || recentIds.contains(toolResult.id())) {
                result.add(workingMessage);
                continue;
            }
            String original = toolResult.text();
            String replacement = clearOldResults
                    ? CLEARED_RESULT
                    : truncateToolResult(original);
            if (replacement.equals(original)) {
                result.add(workingMessage);
                continue;
            }
            changed = true;
            result.add(new WorkingMessage(
                    workingMessage.sequence(),
                    ToolExecutionResultMessage.from(toolResult.id(), toolResult.toolName(), replacement)
            ));
        }
        return new CompactionService.Result(
                result,
                changed ? CompactionService.Strategy.MICRO : CompactionService.Strategy.NONE,
                java.util.Optional.empty()
        );
    }

    /**
     * 统一处理摘要预处理和 Micro Compact 使用的长工具结果截断。
     */
    static String truncateToolResult(String text) {
        if (text == null || text.length() <= TRIM_THRESHOLD_CHARS
                || text.contains(TRUNCATION_MARKER.trim())
                || CLEARED_RESULT.equals(text)) {
            return text;
        }
        return text.substring(0, TRIM_HEAD_CHARS)
                + TRUNCATION_MARKER
                + text.substring(text.length() - TRIM_TAIL_CHARS);
    }
}
