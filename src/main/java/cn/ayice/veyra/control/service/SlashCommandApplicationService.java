package cn.ayice.veyra.control.service;

import cn.ayice.veyra.host.RuntimeHost;
import cn.ayice.veyra.host.CommandOption;
import cn.ayice.veyra.host.CommandResult;
import cn.ayice.veyra.control.dto.command.ExecuteSlashCommandResponse;
import cn.ayice.veyra.control.dto.command.SlashCommandListResponse;
import cn.ayice.veyra.control.dto.command.SlashCommandOptionResponse;
import cn.ayice.veyra.control.exception.AgentApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Slash command 应用服务。命令查询和命令执行都在这里统一进入 runtime。
 */
@Service
public class SlashCommandApplicationService {

    private final RuntimeHost runtimeHost;

    /**
     * 注入该服务运行所需依赖并创建 SlashCommandApplicationService。
     */
    public SlashCommandApplicationService(RuntimeHost runtimeHost) {
        this.runtimeHost = runtimeHost;
    }

    /**
     * 返回与查询条件匹配的命令选项。
     */
    public SlashCommandListResponse options(String sessionId, String query) {
        List<SlashCommandOptionResponse> items = runtimeHost.commandOptions(sessionId, query).stream()
                .map(this::toSlashCommandOptionResponse)
                .toList();
        return new SlashCommandListResponse(items);
    }

    /**
     * 解析并执行斜杠命令，将内部结果映射为控制面响应。
     */
    public ExecuteSlashCommandResponse execute(String sessionId, String command) {
        CommandResult result = runtimeHost.findCommand(sessionId, command)
                .orElseThrow(() -> new AgentApiException(HttpStatus.NOT_FOUND, "unknown slash command"));
        return new ExecuteSlashCommandResponse(result.reason(), result.content());
    }

    /**
     * 把内部命令选项映射为控制面补全响应。
     */
    private SlashCommandOptionResponse toSlashCommandOptionResponse(CommandOption option) {
        return new SlashCommandOptionResponse(option.id(), option.name(), option.description(), option.command());
    }
}
