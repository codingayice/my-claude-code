package cn.ayice.veyra.interaction.command;

import java.util.List;

/**
 * 输入框 slash command。命令暴露可查询的菜单项，执行发生在命令 API，而不是 AgentLoop 消息流程里。
 */
public interface SlashCommand {

    /**
     * 返回与查询条件匹配的命令选项。
     */
    List<SlashCommandOption> options();

    /**
     * 判断输入是否应由当前斜杠命令处理。
     */
    boolean supports(String input);

    /**
     * 执行已匹配的斜杠命令并返回结束或继续进入 Agent 循环的决定。
     */
    SlashCommandResult execute(String input);
}
