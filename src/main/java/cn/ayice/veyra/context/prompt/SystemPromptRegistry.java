package cn.ayice.veyra.context.prompt;


import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统提示词片段注册表。它按顺序执行各 section，并缓存可复用的系统提示词内容。
 */
public class SystemPromptRegistry {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptRegistry.class);

    private final Map<String, SystemPromptSection> sections = new LinkedHashMap<>();
    private final Map<String, String> cache = new HashMap<>();

    /**
     * 注册组件并保持后续构建顺序稳定。
     */
    public void register(SystemPromptSection section) {
        sections.put(section.name(), section);
    }

    /**
     * 清除已构建的系统提示词缓存，使后续请求重新生成。
     */
    public void clearCache() {
        cache.clear();
    }

    /**
     * 根据当前输入构建目标对象。
     */
    public List<ChatMessage> build(SystemPromptContext ctx) {
        List<ChatMessage> messages = new ArrayList<>();
        for (SystemPromptSection section : sections.values()) {
            try {
                String content;
                if (!section.cacheBreak() && cache.containsKey(section.name())) {
                    content = cache.get(section.name());
                } else {
                    content = section.compute(ctx);
                    if (content != null && !content.isBlank()) {
                        if (!section.cacheBreak()) {
                            cache.put(section.name(), content);
                        }
                    }
                }
                if (content != null && !content.isBlank()) {
                    messages.add(SystemMessage.from(content));
                }
            } catch (Exception e) {
                log.error("构建系统提示词 section '{}' 失败", section.name(), e);
            }
        }
        return messages;
    }
}
