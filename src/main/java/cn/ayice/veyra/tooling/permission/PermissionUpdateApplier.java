package cn.ayice.veyra.tooling.permission;

import java.util.List;

/**
 * 权限更新应用器。它把一组 PermissionUpdate 合并进现有上下文，并返回新的 PermissionContext。
 */
public final class PermissionUpdateApplier {

    private PermissionUpdateApplier() {}

    /**
     * 应用给定变更并返回更新后的结果。
     */
    public static PermissionContext apply(PermissionContext context, List<PermissionUpdate> updates) {
        if (updates == null || updates.isEmpty()) {
            return context;
        }
        PermissionContext next = context;
        for (PermissionUpdate update : updates) {
            next = apply(next, update);
        }
        return next;
    }

    /**
     * 应用给定变更并返回更新后的结果。
     */
    public static PermissionContext apply(PermissionContext context, PermissionUpdate update) {
        if (update instanceof PermissionUpdate.SetMode setMode) {
            return context.withMode(setMode.mode());
        }
        if (update instanceof PermissionUpdate.AddRules addRules) {
            PermissionContext next = context;
            for (PermissionRule rule : addRules.rules()) {
                next = next.withRule(rule);
            }
            return next;
        }
        if (update instanceof PermissionUpdate.AddDirectories addDirectories) {
            PermissionContext next = context;
            for (var directory : addDirectories.directories()) {
                next = next.withAllowedDirectory(directory);
            }
            return next;
        }
        return context;
    }
}
