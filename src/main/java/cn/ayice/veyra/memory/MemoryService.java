package cn.ayice.veyra.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 长期记忆的统一业务入口。工具、命令和后台提取都必须通过本服务写入。
 */
public final class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);
    private static final int MAX_NAME_LENGTH = 80;
    private static final int MAX_DESCRIPTION_LENGTH = 200;
    private static final Pattern SENSITIVE_CONTENT = Pattern.compile(
            "(?i)(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|"
                    + "(?:api[_-]?key|token|password|cookie)\\s*[:=]\\s*[^\\s]{8,})"
    );

    private final MemoryFileStore store;

    /**
     * 使用唯一文件存储创建记忆服务。
     */
    public MemoryService(MemoryFileStore store) {
        this.store = store;
    }

    /**
     * 创建或更新一条记忆，并返回可供模型安全判断的明确结果。
     */
    public MemoryOperationResult remember(RememberMemoryCommand command) {
        MemoryEntry entry = null;
        try {
            requireEnabled();
            validateRemember(command);
            String id = command.id() == null || command.id().isBlank()
                    ? store.paths().idFromName(command.name())
                    : store.paths().validateId(command.id());
            Optional<MemoryEntry> existing = store.read(command.scope(), id);
            Instant now = Instant.now();
            entry = new MemoryEntry(
                    id,
                    command.scope(),
                    command.type(),
                    command.activation(),
                    command.name().trim(),
                    command.description().trim(),
                    command.content().trim(),
                    existing.map(MemoryEntry::createdAt).orElse(now),
                    now,
                    blankToNull(command.sourceSessionId())
            );
            store.write(entry);
            log.info("长期记忆写入成功, id={}, scope={}, type={}", id, entry.scope(), entry.type());
            return MemoryOperationResult.success(existing.isPresent() ? "记忆已更新" : "记忆已保存", entry);
        } catch (MemoryException error) {
            log.error("长期记忆写入失败, code={}", error.code(), error);
            if (error.code() == MemoryErrorCode.MEMORY_INDEX_REBUILD_FAILED) {
                return MemoryOperationResult.partial(error.code(), error.getMessage(), entry);
            }
            return MemoryOperationResult.failure(error.code(), error.getMessage());
        }
    }

    /**
     * 删除一条记忆，未命中和持久化失败都会返回稳定错误码。
     */
    public MemoryOperationResult forget(ForgetMemoryCommand command) {
        try {
            requireEnabled();
            if (command == null || command.scope() == null) {
                throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "必须指定记忆作用域");
            }
            String id = store.paths().validateId(command.id());
            if (!store.delete(command.scope(), id)) {
                return MemoryOperationResult.failure(MemoryErrorCode.MEMORY_NOT_FOUND, "未找到记忆: " + id);
            }
            log.info("长期记忆删除成功, id={}, scope={}", id, command.scope());
            return MemoryOperationResult.success("记忆已删除", null);
        } catch (MemoryException error) {
            log.error("长期记忆删除失败, code={}", error.code(), error);
            if (error.code() == MemoryErrorCode.MEMORY_INDEX_REBUILD_FAILED) {
                return MemoryOperationResult.partial(error.code(), "记忆已删除，但索引重建失败", null);
            }
            return MemoryOperationResult.failure(error.code(), error.getMessage());
        }
    }

    /**
     * 返回指定作用域的索引元数据。
     */
    public List<MemoryIndexEntry> list(MemoryScope scope) {
        requireEnabled();
        return store.list(scope).stream().map(MemoryIndexEntry::from).toList();
    }

    /**
     * 返回指定作用域中的完整记忆；未命中时抛出类型化异常。
     */
    public MemoryEntry show(MemoryScope scope, String id) {
        requireEnabled();
        return store.read(scope, id)
                .orElseThrow(() -> new MemoryException(MemoryErrorCode.MEMORY_NOT_FOUND, "未找到记忆: " + id));
    }

    /**
     * 从 topic 重建指定作用域索引。
     */
    public void rebuild(MemoryScope scope) {
        requireEnabled();
        store.rebuildIndex(scope);
    }

    /**
     * 返回长期记忆功能是否启用。
     */
    public boolean isEnabled() {
        return !Files.exists(disabledMarker());
    }

    /**
     * 更新长期记忆总开关；失败时抛出类型化异常，禁止静默吞掉。
     */
    public void setEnabled(boolean enabled) {
        try {
            Path marker = disabledMarker();
            Files.createDirectories(marker.getParent());
            if (enabled) {
                Files.deleteIfExists(marker);
            } else {
                Files.writeString(marker, "disabled\n", StandardCharsets.UTF_8);
            }
        } catch (Exception error) {
            throw new MemoryException(MemoryErrorCode.MEMORY_WRITE_FAILED, "更新长期记忆开关失败", error);
        }
    }

    /**
     * 返回底层路径信息，供人工维护命令展示，不允许调用方自行拼接写路径。
     */
    public MemoryPaths paths() {
        return store.paths();
    }

    /**
     * 校验模型或外部输入，避免无效数据和明显敏感信息落盘。
     */
    private void validateRemember(RememberMemoryCommand command) {
        if (command == null || command.scope() == null || command.type() == null || command.activation() == null) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "记忆作用域、类型和激活方式不能为空");
        }
        String name = required(command.name(), "记忆名称不能为空");
        String description = required(command.description(), "记忆描述不能为空");
        String content = required(command.content(), "记忆正文不能为空");
        if (name.length() > MAX_NAME_LENGTH || description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "记忆名称或描述超过长度限制");
        }
        if (command.activation() == MemoryActivation.ALWAYS && command.scope() != MemoryScope.USER) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "ALWAYS 仅允许用于用户级稳定偏好");
        }
        if (command.activation() == MemoryActivation.ALWAYS && command.type() != MemoryType.PREFERENCE) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "ALWAYS 仅允许用于用户偏好");
        }
        if (SENSITIVE_CONTENT.matcher(name + "\n" + description + "\n" + content).find()) {
            throw new MemoryException(MemoryErrorCode.MEMORY_SENSITIVE_CONTENT, "记忆内容包含疑似敏感信息，已拒绝保存");
        }
    }

    /**
     * 在所有读写入口统一执行长期记忆总开关，避免调用方各自判断。
     */
    private void requireEnabled() {
        if (!isEnabled()) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, "长期记忆已关闭");
        }
    }

    /**
     * 定位长期记忆根目录下的全局关闭标记。
     */
    private Path disabledMarker() {
        return store.paths().root().resolve(".disabled");
    }

    /**
     * 规范化必填文本，并把空值转换为稳定的请求错误。
     */
    private static String required(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new MemoryException(MemoryErrorCode.MEMORY_INVALID_REQUEST, message);
        }
        return value.trim();
    }

    /**
     * 将可选输入规范化为空值或去除首尾空白的文本。
     */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
