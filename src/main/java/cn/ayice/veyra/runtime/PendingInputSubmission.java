package cn.ayice.veyra.runtime;

/** 运行中输入进入追随队列后的即时响应。 */
public record PendingInputSubmission(String messageId, boolean accepted, boolean steerable) {

    /** 返回输入校验未通过时的拒绝结果。 */
    public static PendingInputSubmission rejected() {
        return new PendingInputSubmission("", false, false);
    }

    /** 返回已经进入追随队列的受理结果。 */
    public static PendingInputSubmission accepted(PendingInputQueue.Message message) {
        return new PendingInputSubmission(message.id(), true, message.mode() == RunMode.AGENT);
    }
}
