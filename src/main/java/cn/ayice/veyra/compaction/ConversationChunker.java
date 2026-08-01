package cn.ayice.veyra.compaction;

import cn.ayice.veyra.context.TokenEstimator;
import cn.ayice.veyra.context.WorkingMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 摘要请求的纯切分组件，按真实用户回合组织消息并在预算边界上生成块。
 */
public final class ConversationChunker {

    /**
     * 按完整用户回合切分，普通历史能够容纳时只返回一个块。
     */
    public List<List<WorkingMessage>> split(List<WorkingMessage> messages, int maxInputTokens) {
        if (maxInputTokens <= 0) {
            throw new IllegalArgumentException("maxInputTokens must be positive");
        }
        List<List<WorkingMessage>> turns = groupTurns(messages);
        List<List<WorkingMessage>> chunks = new ArrayList<>();
        List<WorkingMessage> current = new ArrayList<>();
        int currentTokens = 0;
        for (List<WorkingMessage> turn : turns) {
            int turnTokens = TokenEstimator.estimate(WorkingMessage.unwrap(turn));
            if (!current.isEmpty() && currentTokens + turnTokens > maxInputTokens) {
                chunks.add(List.copyOf(current));
                current.clear();
                currentTokens = 0;
            }
            current.addAll(turn);
            currentTokens += turnTokens;
        }
        if (!current.isEmpty()) {
            chunks.add(List.copyOf(current));
        }
        return List.copyOf(chunks);
    }

    /**
     * 以带 sequence 的真实用户消息为回合起点，保留该回合后续助手消息和工具批次。
     */
    private static List<List<WorkingMessage>> groupTurns(List<WorkingMessage> messages) {
        List<List<WorkingMessage>> turns = new ArrayList<>();
        List<WorkingMessage> current = new ArrayList<>();
        for (WorkingMessage message : messages) {
            boolean startsRealUserTurn = message.sequence().isPresent()
                    && message.message() instanceof UserMessage;
            if (startsRealUserTurn && !current.isEmpty()) {
                turns.add(List.copyOf(current));
                current.clear();
            }
            current.add(message);
        }
        if (!current.isEmpty()) {
            turns.add(List.copyOf(current));
        }
        return turns;
    }
}
