package cn.ayice.veyra.control.dto.run;

/**
 * 前端用一段用户输入启动一次 agent/chat 运行。
 */
public record CreateRunRequest(String input, String mode) {
}
