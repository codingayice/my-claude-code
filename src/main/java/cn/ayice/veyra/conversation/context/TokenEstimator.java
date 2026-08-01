package cn.ayice.veyra.conversation.context;


import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ContentType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;

import java.util.List;

/**
 * 轻量 token 估算器。它服务于压缩决策，优先保证简单快速，不追求和模型 tokenizer 完全一致。
 */
public class TokenEstimator {

    private static final int IMAGE_TOKEN_SIZE = 2000;
    private static final double SAFETY_MARGIN = 4.0 / 3.0;

    /**
     * 估算消息或消息集合占用的 token 数。
     */
    public static int estimate(List<ChatMessage> messages) {
        int total = 0;
        for (ChatMessage msg : messages) {
            total += estimate(msg);
        }
        return (int) Math.ceil(total * SAFETY_MARGIN);
    }

    /**
     * 估算消息或消息集合占用的 token 数。
     */
    public static int estimate(ChatMessage msg) {
        if (msg instanceof UserMessage um) {
            return estimateUserMessage(um);
        } else if (msg instanceof AiMessage am) {
            int total = estimateText(am.text());
            if (am.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : am.toolExecutionRequests()) {
                    total += estimateText(request.name());
                    total += estimateText(request.arguments());
                }
            }
            return total;
        } else if (msg instanceof ToolExecutionResultMessage trm) {
            return estimateText(trm.text());
        } else if (msg instanceof SystemMessage sm) {
            return estimateText(sm.text());
        }
        return estimateText(msg.toString());
    }

    /**
     * 估算用户消息消息。
     */
    private static int estimateUserMessage(UserMessage msg) {
        List<Content> contents = msg.contents();
        if (contents == null || contents.isEmpty()) {
            return estimateText(msg.singleText());
        }
        int total = 0;
        for (Content content : contents) {
            if (content.type() == ContentType.IMAGE || content.type() == ContentType.PDF) {
                total += IMAGE_TOKEN_SIZE;
            } else {
                total += estimateText(content.toString());
            }
        }
        return total;
    }

    /**
     * 估算文本。
     */
    public static int estimateText(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double tokens = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                i++;
                tokens += 2;
            } else if (isCJK(c)) {
                tokens += 0.65;
            } else if (Character.isWhitespace(c)) {
                tokens += 0.1;
            } else if (Character.isDigit(c)) {
                tokens += 0.2;
            } else {
                tokens += 0.25;
            }
        }
        return Math.max(1, (int) Math.ceil(tokens));
    }

    /**
     * 判断字符是否属于按单字符估算 token 的中日韩文字区段。
     */
    private static boolean isCJK(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES
                || block == Character.UnicodeBlock.HANGUL_JAMO
                || block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO;
    }
}
