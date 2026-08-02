package cn.ayice.veyra.tool.permission;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * 当前会话权限上下文的可变持有者。用户临时授权后，主循环和子 agent 运行时通过它共享更新。
 */
public class PermissionContextStore {
    private final AtomicReference<PermissionContext> current;

    public PermissionContextStore(PermissionContext initialContext) {
        this.current = new AtomicReference<>(initialContext);
    }

    /**
     * 返回当前不可变权限上下文快照。
     */
    public PermissionContext current() {
        return current.get();
    }

    /**
     * 应用给定变更并返回更新后的结果。
     */
    public PermissionContext apply(List<Update> updates) {
        if (updates == null || updates.isEmpty()) {
            return current();
        }
        return update(context -> applyTo(context, updates));
    }

    /**
     * 原子替换权限上下文并返回更新后的不可变快照。
     */
    public PermissionContext update(UnaryOperator<PermissionContext> updater) {
        while (true) {
            PermissionContext before = current.get();
            PermissionContext after = updater.apply(before);
            if (current.compareAndSet(before, after)) {
                return after;
            }
        }
    }

    /**
     * 将一组声明式更新应用到不可变权限上下文。
     */
    public static PermissionContext applyTo(PermissionContext context, List<Update> updates) {
        PermissionContext next = context;
        for (Update change : updates) {
            if (change instanceof Update.SetMode setMode) {
                next = next.withMode(setMode.mode());
            } else if (change instanceof Update.AddRules addRules) {
                for (PermissionRule rule : addRules.rules()) {
                    next = next.withRule(rule);
                }
            } else if (change instanceof Update.AddDirectories addDirectories) {
                for (Path directory : addDirectories.directories()) {
                    next = next.withAllowedDirectory(directory);
                }
            }
        }
        return next;
    }

    /**
     * 用户批准工具调用后对 PermissionContext 的声明式更新。
     */
    public sealed interface Update permits Update.SetMode, Update.AddRules, Update.AddDirectories {
        /**
         * 创建权限模式更新。
         */
        static Update setMode(PermissionMode mode) {
            return new SetMode(mode);
        }

        /**
         * 创建单条权限规则追加更新。
         */
        static Update addRule(PermissionRule rule) {
            return addRules(List.of(rule));
        }

        /**
         * 创建多条权限规则追加更新。
         */
        static Update addRules(List<PermissionRule> rules) {
            return new AddRules(List.copyOf(rules));
        }

        /**
         * 创建单个允许目录追加更新。
         */
        static Update addDirectory(Path directory) {
            return addDirectories(List.of(directory));
        }

        /**
         * 创建多个允许目录追加更新。
         */
        static Update addDirectories(List<Path> directories) {
            return new AddDirectories(List.copyOf(directories));
        }

        /**
         * 权限模式替换更新。
         */
        record SetMode(PermissionMode mode) implements Update {
        }

        /**
         * 权限规则追加更新。
         */
        record AddRules(List<PermissionRule> rules) implements Update {
        }

        /**
         * 允许目录追加更新。
         */
        record AddDirectories(List<Path> directories) implements Update {
        }
    }
}
