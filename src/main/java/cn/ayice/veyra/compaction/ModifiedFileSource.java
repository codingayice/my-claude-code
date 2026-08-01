package cn.ayice.veyra.compaction;

import java.nio.file.Path;
import java.util.List;

/**
 * 压缩完成后获取最近修改文件路径的窄回调，避免 Compaction 依赖具体工具状态实现。
 */
@FunctionalInterface
public interface ModifiedFileSource {

    /**
     * 返回不超过指定数量的最近修改文件路径。
     */
    List<Path> recentModifiedPaths(int limit);
}
