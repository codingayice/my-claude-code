package cn.ayice.veyra.memory;

/**
 * 按作用域和稳定标识删除长期记忆的结构化命令。
 */
public record ForgetMemoryCommand(MemoryScope scope, String id) {
}
