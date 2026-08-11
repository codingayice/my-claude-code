package cn.ayice.veyra.control.dto.run;

/** 追随输入进入会话队列后的响应。 */
public record CreateFollowupResponse(String messageId, boolean accepted, boolean steerable) {
}
