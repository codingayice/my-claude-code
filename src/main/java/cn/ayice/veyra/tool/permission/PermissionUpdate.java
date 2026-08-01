package cn.ayice.veyra.tool.permission;

import java.nio.file.Path;
import java.util.List;

/**
 * 对 PermissionContext 的声明式更新。用户批准工具调用后，会用它描述要加入的目录或规则。
 */
public sealed interface PermissionUpdate
        permits PermissionUpdate.SetMode, PermissionUpdate.AddRules, PermissionUpdate.AddDirectories {

    /**
     * 将权限模式更新为给定值。
     */
    static PermissionUpdate setMode(PermissionMode mode) {
        return new SetMode(mode);
    }

    /**
     * 将给定项加入权限规则。
     */
    static PermissionUpdate addRule(PermissionRule rule) {
        return addRules(List.of(rule));
    }

    /**
     * 将给定项加入权限规则集合。
     */
    static PermissionUpdate addRules(List<PermissionRule> rules) {
        return new AddRules(List.copyOf(rules));
    }

    /**
     * 将给定项加入目录。
     */
    static PermissionUpdate addDirectory(Path directory) {
        return addDirectories(List.of(directory));
    }

    /**
     * 将给定项加入目录集合。
     */
    static PermissionUpdate addDirectories(List<Path> directories) {
        return new AddDirectories(List.copyOf(directories));
    }

    /**
     * SetMode 枚举对应流程允许的离散状态。
     */
    record SetMode(PermissionMode mode) implements PermissionUpdate {}

    /**
     * 一次向权限上下文追加多条规则的更新。
     */
    record AddRules(List<PermissionRule> rules) implements PermissionUpdate {}

    /**
     * 一次向权限上下文追加多个允许目录的更新。
     */
    record AddDirectories(List<Path> directories) implements PermissionUpdate {}
}
