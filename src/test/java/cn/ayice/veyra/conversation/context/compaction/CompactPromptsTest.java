package cn.ayice.veyra.conversation.context.compaction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactPromptsTest {

    @Test
    void compactPromptRequiresMarkdownWithoutAnalysisOrToolCalls() {
        String prompt = CompactPrompts.buildChunkSummaryPrompt("用户: 示例任务");

        assertTrue(prompt.contains("## 当前目标"));
        assertTrue(prompt.contains("## 用户约束"));
        assertTrue(prompt.contains("## 下一步"));
        assertTrue(prompt.contains("不要调用工具"));
        assertTrue(prompt.contains("不要输出分析过程"));

        assertFalse(prompt.contains("<analysis>"));
        assertFalse(prompt.contains("<summary>"));
        assertFalse(prompt.contains("Your task is"));
    }
}
