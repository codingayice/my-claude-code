package cn.ayice.veyra.boot;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.context.ContextService;
import cn.ayice.veyra.compaction.CompactionConfig;
import cn.ayice.veyra.compaction.SummaryCompactor;
import cn.ayice.veyra.compaction.CheckpointState;
import cn.ayice.veyra.compaction.BackgroundSummaryScheduler;
import cn.ayice.veyra.runtime.session.SessionRuntime;
import cn.ayice.veyra.runtime.session.RuntimeSessionRegistry;
import cn.ayice.veyra.runtime.session.ToolApprovalQueue;
import cn.ayice.veyra.session.event.SessionAgentEventSink;
import cn.ayice.veyra.session.event.SessionEventStream;
import cn.ayice.veyra.interaction.command.SlashCommands;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.runtime.MemoryExtractionCoordinator;
import cn.ayice.veyra.memory.MemoryFileStore;
import cn.ayice.veyra.memory.MemoryPaths;
import cn.ayice.veyra.memory.MemoryService;
import cn.ayice.veyra.tool.permission.PermissionContext;
import cn.ayice.veyra.tool.permission.PermissionContextStore;
import cn.ayice.veyra.tool.permission.PermissionMode;
import cn.ayice.veyra.runtime.agent.AgentLoop;
import cn.ayice.veyra.subagent.AgentProfile;
import cn.ayice.veyra.subagent.SubagentRuntime;
import cn.ayice.veyra.subagent.SubagentService;
import cn.ayice.veyra.tool.background.BackgroundManager;
import cn.ayice.veyra.runtime.chat.ChatLoop;
import cn.ayice.veyra.session.persistence.StoreBackedTranscriptRecorder;
import cn.ayice.veyra.session.persistence.TranscriptStore;
import cn.ayice.veyra.tool.BaseTool;
import cn.ayice.veyra.tool.ToolCatalog;
import cn.ayice.veyra.subagent.tool.AgentTool;
import cn.ayice.veyra.tool.background.BackgroundRunTool;
import cn.ayice.veyra.tool.builtin.BashTool;
import cn.ayice.veyra.subagent.tool.CheckTaskTool;
import cn.ayice.veyra.tool.builtin.FileEditTool;
import cn.ayice.veyra.tool.builtin.FileReadTool;
import cn.ayice.veyra.tool.builtin.FileWriteTool;
import cn.ayice.veyra.tool.builtin.GlobTool;
import cn.ayice.veyra.tool.builtin.GrepTool;
import cn.ayice.veyra.memory.tool.MemoryTool;
import cn.ayice.veyra.subagent.tool.StopTaskTool;
import cn.ayice.veyra.tool.builtin.TodoWriteTool;
import cn.ayice.veyra.tool.state.FileStateCache;
import cn.ayice.veyra.tool.state.TodoManager;
import cn.ayice.veyra.subagent.AgentProfile.PermissionPolicy;
import dev.langchain4j.data.message.ChatMessage;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个会话运行时的对象装配入口。
 * <p>该类集中创建 Agent、工具、权限、记忆和事件组件，业务包不得在其他位置重复拼装完整对象图。</p>
 */
public class SessionRuntimeFactory implements RuntimeSessionRegistry.Factory {

    private static final Logger log = LoggerFactory.getLogger(SessionRuntimeFactory.class);

    private final AppConfig config;
    private final TranscriptStore transcriptStore;
    private final Executor runExecutor;
    private final Executor taskExecutor;
    private final Executor ioExecutor;
    private final AIService ai;
    private final MemoryService memoryService;

    /**
     * 使用全局配置、转录存储和受 Spring 管理的线程池创建会话工厂。
     */
    public SessionRuntimeFactory(
            AppConfig config,
            TranscriptStore transcriptStore,
            Executor runExecutor,
            Executor taskExecutor,
            Executor ioExecutor
    ) {
        this.config = config;
        this.transcriptStore = transcriptStore;
        this.runExecutor = runExecutor;
        this.taskExecutor = taskExecutor;
        this.ioExecutor = ioExecutor;
        this.ai = new AIService(config);
        String workspace = canonicalWorkspace(config.getWorkspace());
        MemoryPaths paths = new MemoryPaths(config.getLongTermMemoryDir(), workspace);
        MemoryFileStore store = new MemoryFileStore(
                paths,
                config.getMemoryMaxTopicBytes(),
                config.getMemoryMaxIndexLines(),
                config.getMemoryMaxIndexBytes(),
                config.getMemoryMaxScannedTopics()
        );
        this.memoryService = new MemoryService(
                store,
                ai,
                ioExecutor,
                config.getMemoryMaxAlwaysContextBytes(),
                config.getMemoryMaxRecallItems(),
                config.getMemoryMaxRecalledTopicBytes(),
                config.getMemoryMaxTurnContextBytes()
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public SessionRuntime create(String sessionId, List<ChatMessage> initialHistory) {
        // 首先创建会话独占的事件、审批、权限和转录组件，避免可变状态跨会话共享。
        SessionEventStream events = new SessionEventStream(sessionId);
        ToolApprovalQueue confirmation = new ToolApprovalQueue(events);
        PermissionContextStore permissionContextStore = new PermissionContextStore(buildPermissionContext(config));
        SessionAgentEventSink eventSink = new SessionAgentEventSink(events);
        StoreBackedTranscriptRecorder recorder = new StoreBackedTranscriptRecorder(sessionId, transcriptStore);

        FileStateCache fileStateCache = new FileStateCache();
        SubagentRuntime agentRuntime = new SubagentRuntime(
                ai,
                config,
                confirmation,
                eventSink,
                permissionContextStore,
                this::createSubagentToolCatalog
        );
        SubagentService subagentService = new SubagentService(agentRuntime, taskExecutor, eventSink::emit);
        BackgroundManager backgroundManager = new BackgroundManager(ioExecutor, eventSink::emit);
        TodoManager todoManager = new TodoManager(eventSink::emit);
        ToolCatalog toolCatalog = ToolCatalog.create(List.of(
                new BashTool(),
                new FileReadTool(fileStateCache),
                new FileEditTool(fileStateCache),
                new FileWriteTool(fileStateCache),
                new GlobTool(),
                new GrepTool(ioExecutor),
                new AgentTool(subagentService),
                new BackgroundRunTool(backgroundManager),
                new CheckTaskTool(subagentService, backgroundManager),
                new StopTaskTool(subagentService, backgroundManager),
                new MemoryTool(memoryService),
                new TodoWriteTool(todoManager)
        ), fileStateCache);
        // 上下文、记忆和压缩服务共享同一份会话工具元数据与持久记忆目录。
        CompactionConfig compactConfig = CompactionConfig.from(config);
        ContextService contextBuilder = new ContextService(
                toolCatalog.specifications(),
                toolCatalog.descriptions(),
                config,
                memoryService,
                compactConfig.contextTokenBudget()
        );
        CheckpointState checkpointState = new CheckpointState();
        CompactionConfig.SummaryPolicy sessionSummaryConfig = CompactionConfig.SummaryPolicy.defaults();
        if (sessionSummaryConfig.maxInputTokens() + sessionSummaryConfig.maxSummaryTokens()
                > compactConfig.effectiveWindow()
                || SummaryCompactor.DEFAULT_MAX_CHUNK_INPUT_TOKENS
                + SummaryCompactor.DEFAULT_MAX_OUTPUT_TOKENS > compactConfig.effectiveWindow()) {
            throw new IllegalArgumentException("compaction summary request exceeds the effective context window");
        }
        BackgroundSummaryScheduler sessionSummaryCoordinator = new BackgroundSummaryScheduler(
                new SummaryCompactor(ai, sessionSummaryConfig),
                checkpointState,
                ioExecutor,
                sessionSummaryConfig,
                eventSink::emit
        );
        MemoryExtractionCoordinator memoryExtraction = config.isAutoMemoryExtractionEnabled()
                ? new MemoryExtractionCoordinator(
                        sessionId,
                        memoryService,
                        agentRuntime,
                        ioExecutor,
                        config.getMemoryExtractionMaxRounds(),
                        initialHistory == null ? 0L : initialHistory.size()
                )
                : null;
        // AgentLoop 与 ChatLoop 共享模型和 transcript，但保持不同的执行策略和历史副本。
        AgentLoop agentLoop = new AgentLoop(
                ai,
                toolCatalog,
                contextBuilder,
                backgroundManager,
                confirmation,
                permissionContextStore,
                todoManager,
                compactConfig,
                config.getMaxRounds(),
                subagentService,
                eventSink,
                checkpointState,
                sessionSummaryCoordinator,
                fileStateCache,
                config.getModelTimeoutSeconds() * 1000L,
                memoryExtraction,
                initialHistory,
                recorder,
                taskExecutor
        );
        ChatLoop chatLoop = new ChatLoop(
                ai,
                eventSink,
                config.getModelTimeoutSeconds() * 1000L,
                initialHistory,
                recorder
        );
        return new SessionRuntime(
                sessionId,
                events,
                confirmation,
                agentLoop,
                chatLoop,
                permissionContextStore,
                SlashCommands.builtIns(
                        memoryService,
                        memoryExtraction == null ? null : memoryExtraction::status,
                        agentLoop::compactNow,
                        agentLoop::compactionStatus
                ),
                runExecutor
        );
    }

    /**
     * 在 Boot 装配边界为一次子 Agent 执行创建独占工具实例和文件状态缓存。
     */
    private ToolCatalog createSubagentToolCatalog(AgentProfile profile) {
        FileStateCache fileStateCache = new FileStateCache();
        PermissionPolicy policy = profile.permissionPolicy();
        List<BaseTool> tools = new ArrayList<>();
        tools.add(new FileReadTool(fileStateCache));
        tools.add(new FileEditTool(fileStateCache));
        tools.add(new FileWriteTool(fileStateCache));
        tools.add(new GlobTool());
        tools.add(new GrepTool(ioExecutor));
        tools.add(new BashTool(policy.readOnlyBash()));
        tools.add(new MemoryTool(memoryService));
        return ToolCatalog.create(tools, fileStateCache).profile(profile.toolProfile());
    }

    /**
     * 根据固定工作区和配置的权限模式创建会话初始权限上下文。
     */
    private static PermissionContext buildPermissionContext(AppConfig config) {
        Path workspacePath = Path.of(config.getWorkspace());
        return PermissionContext.builder()
                .mode(PermissionMode.fromString(config.getPermissionMode()))
                .workingDir(workspacePath)
                .addAllowedDirectory(workspacePath)
                .build();
    }

    /**
     * 返回规范化的工作区绝对路径；路径暂时不可解析时退回到 normalize 结果。
     */
    private static String canonicalWorkspace(String workspace) {
        Path path = Path.of(workspace == null || workspace.isBlank()
                ? System.getProperty("user.dir")
                : workspace).toAbsolutePath().normalize();
        try {
            return path.toRealPath().toString();
        } catch (Exception canonicalizationError) {
            log.debug("工作区路径无法解析为 real path: {}", path, canonicalizationError);
            return path.toString();
        }
    }
}
