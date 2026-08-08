package cn.ayice.veyra.session.recovery;

import cn.ayice.veyra.session.persistence.JournalMessageCodec;
import cn.ayice.veyra.session.persistence.SessionJournalStore;
import cn.ayice.veyra.session.persistence.SessionJournalTypes;
import cn.ayice.veyra.session.persistence.SessionPathResolver;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;

import java.nio.file.Path;
import java.util.Map;

/**
 * 故障测试使用的独立 Java 进程，在稳定边界后不执行 shutdown hook 直接退出。
 */
public final class JournalCrashHarness {

    private JournalCrashHarness() {
    }

    /**
     * 写入工具 started 事实后使用 Runtime.halt 模拟进程被强制终止。
     */
    public static void main(String[] args) {
        Path root = Path.of(args[0]);
        SessionJournalStore store = new SessionJournalStore(new SessionPathResolver(root.toString(), "crash-workspace"));
        store.append("crash-session", null, SessionJournalTypes.SESSION_CREATED, Map.of(
                "workingDir", root.toString(), "permissionMode", "ask"
        ), true);
        store.append("crash-session", "crash-run", SessionJournalTypes.RUN_STARTED,
                Map.of("mode", "agent"), false);
        store.append("crash-session", "crash-run", SessionJournalTypes.USER_MESSAGE_RECORDED,
                Map.of("text", "修改 README", "visible", true), true);
        ToolExecutionRequest request = ToolExecutionRequest.builder()
                .id("crash-tool").name("FileEdit").arguments("{\"path\":\"README.md\"}").build();
        store.append("crash-session", "crash-run", SessionJournalTypes.ASSISTANT_MESSAGE_RECORDED,
                JournalMessageCodec.encode(AiMessage.from(request)), true);
        store.append("crash-session", "crash-run", SessionJournalTypes.TOOL_EXECUTION_STARTED,
                Map.of("toolUseId", "crash-tool", "name", "FileEdit"), true);
        Runtime.getRuntime().halt(23);
    }
}
