package cn.ayice.veyra.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 单个 Session 的运行中输入邮箱。消息默认等待后续 Run，也可以原子移动到当前 AgentLoop 的引导队列。
 */
public final class PendingInputQueue {

    private final Deque<Message> followups = new ArrayDeque<>();
    private final Deque<Message> steers = new ArrayDeque<>();

    /** 将一条输入追加到追随队列。 */
    public synchronized Message addFollowup(String text, RunMode mode) {
        Message message = new Message(UUID.randomUUID().toString(), text, mode);
        followups.addLast(message);
        return message;
    }

    /** 将尚未消费的追随消息移动到引导队列。 */
    public synchronized boolean steer(String messageId) {
        Message found = find(followups, messageId);
        if (found == null || found.mode() != RunMode.AGENT) {
            return false;
        }
        followups.remove(found);
        steers.addLast(found);
        return true;
    }

    /** 在 AgentLoop 轮次边界按提交顺序取走全部引导消息。 */
    public synchronized List<Message> drainSteers() {
        List<Message> result = new ArrayList<>(steers);
        steers.clear();
        return List.copyOf(result);
    }

    /**
     * 当前 Run 结束后领取消息。过晚而未被 AgentLoop 消费的引导会自动退化为下一次 Run。
     */
    public synchronized Message takeForNextRun(String messageId) {
        Message found = find(followups, messageId);
        if (found != null) {
            followups.remove(found);
            return found;
        }
        found = find(steers, messageId);
        if (found != null) {
            steers.remove(found);
        }
        return found;
    }

    /** 按稳定标识在指定队列中查找消息，不改变队列内容。 */
    private static Message find(Deque<Message> queue, String messageId) {
        if (messageId == null) {
            return null;
        }
        return queue.stream()
                .filter(message -> message.id().equals(messageId))
                .findFirst()
                .orElse(null);
    }

    /** 一条待处理用户输入。 */
    public record Message(String id, String text, RunMode mode) {
    }
}
