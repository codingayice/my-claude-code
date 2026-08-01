package cn.ayice.veyra.tool.permission;

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
    public PermissionContext apply(List<PermissionUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return current();
        }
        return update(context -> PermissionUpdateApplier.apply(context, updates));
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
}
