package cn.ayice.veyra.control.dto.command;

import java.util.List;

/**
 * slash command 查询响应。
 */
public record SlashCommandListResponse(List<SlashCommandOptionResponse> items) {
}
