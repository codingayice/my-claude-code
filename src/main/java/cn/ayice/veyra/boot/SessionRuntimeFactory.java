package cn.ayice.veyra.boot;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.conversation.context.ContextBuilder;
import cn.ayice.veyra.conversation.context.compaction.AutoCompactConfig;
import cn.ayice.veyra.conversation.context.compaction.ConversationChunker;
import cn.ayice.veyra.conversation.context.compaction.LlmSummaryCompactor;
import cn.ayice.veyra.conversation.context.compaction.SessionCheckpointState;
import cn.ayice.veyra.conversation.context.compaction.SessionSummaryCoordinator;
import cn.ayice.veyra.conversation.context.compaction.SessionSummaryConfig;
import cn.ayice.veyra.conversation.context.compaction.SessionSummaryGenerator;
import cn.ayice.veyra.host.SessionRuntime;
import cn.ayice.veyra.host.SessionRuntimeCreator;
import cn.ayice.veyra.host.ToolApprovalQueue;
import cn.ayice.veyra.host.event.SessionAgentEventSink;
import cn.ayice.veyra.host.event.SessionEventStream;
import cn.ayice.veyra.interaction.command.SlashCommands;
import cn.ayice.veyra.llm.AIService;
import cn.ayice.veyra.conversation.memory.MemoryContextBuilder;
import cn.ayice.veyra.kernel.memory.MemoryExtractionCoordinator;
import cn.ayice.veyra.conversation.memory.MemoryFileStore;
import cn.ayice.veyra.conversation.memory.MemoryPaths;
import cn.ayice.veyra.conversation.memory.MemoryRecallService;
import cn.ayice.veyra.conversation.memory.MemoryService;
import cn.ayice.veyra.tooling.permission.PermissionContext;
import cn.ayice.veyra.tooling.permission.PermissionContextStore;
import cn.ayice.veyra.tooling.permission.PermissionMode;
import cn.ayice.veyra.kernel.agent.AgentLoop;
import cn.ayice.veyra.kernel.subagent.SubagentRuntime;
import cn.ayice.veyra.tooling.task.AgentTaskManager;
import cn.ayice.veyra.tooling.task.BackgroundManager;
import cn.ayice.veyra.kernel.chat.ChatLoop;
import cn.ayice.veyra.conversation.transcript.StoreBackedTranscriptRecorder;
import cn.ayice.veyra.conversation.transcript.TranscriptStore;
import cn.ayice.veyra.tooling.BaseTool;
import cn.ayice.veyra.tooling.ToolDispatcher;
import cn.ayice.veyra.tooling.ToolRegistry;
import cn.ayice.veyra.tooling.builtin.AgentTool;
import cn.ayice.veyra.tooling.builtin.BackgroundRunTool;
import cn.ayice.veyra.tooling.builtin.BashTool;
import cn.ayice.veyra.tooling.builtin.CheckTaskTool;
import cn.ayice.veyra.tooling.builtin.FileEditTool;
import cn.ayice.veyra.tooling.builtin.FileReadTool;
import cn.ayice.veyra.tooling.builtin.FileWriteTool;
import cn.ayice.veyra.tooling.builtin.GlobTool;
import cn.ayice.veyra.tooling.builtin.GrepTool;
import cn.ayice.veyra.kernel.memory.MemoryTool;
import cn.ayice.veyra.tooling.builtin.StopTaskTool;
import cn.ayice.veyra.tooling.builtin.TodoWriteTool;
import cn.ayice.veyra.tooling.state.FileStateCache;
import cn.ayice.veyra.tooling.state.TodoManager;
import dev.langchain4j.data.message.ChatMessage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单个会话运行时的对象装配入口。
 * <p>该类集中创建 Agent、工具、权限、记忆和事件组件，业务包不得在其他位置重复拼装完整对象图。</p>
 */
public class SessionRuntimeFactory implements SessionRuntimeCreator {

    private static final Logger log = LoggerFactory.getLogger(SessionRuntimeFactory.class);

    private final AppConfig config;
    private final TranscriptStore transcriptStore;
    private final Executor runExecutor;
    private final Executor taskExecutor;
    private final Executor ioExecutor;
    private final AIService ai;
    private final MemoryService memoryService;
    private final MemoryContextBuilder memoryContextBuilder;

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
        this.memoryService = new MemoryService(store);
        this.memoryContextBuilder = new MemoryContextBuilder(
                memoryService,
                store,
                new MemoryRecallService(store),
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

        // Registry 决定模型可见的工具，Dispatcher 决定实际可执行的工具；所有工具必须同时注册到两者。
        ToolRegistry registry = new ToolRegistry();
        ToolDispatcher dispatcher = new ToolDispatcher();
        FileStateCache fileStateCache = new FileStateCache();
        SubagentRuntime agentRuntime = new SubagentRuntime(
                ai, config, memoryService, confirmation, eventSink, permissionContextStore, ioExecutor);
        AgentTaskManager agentTaskManager = new AgentTaskManager(agentRuntime, taskExecutor, eventSink::emit);
        register(registry, dispatcher, new BashTool());
        register(registry, dispatcher, new FileReadTool(fileStateCache));
        register(registry, dispatcher, new FileEditTool(fileStateCache));
        register(registry, dispatcher, new FileWriteTool(fileStateCache));
        register(registry, dispatcher, new GlobTool());
        register(registry, dispatcher, new GrepTool(ioExecutor));
        register(registry, dispatcher, new AgentTool(agentTaskManager));
        BackgroundManager backgroundManager = new BackgroundManager(ioExecutor, eventSink::emit);
        register(registry, dispatcher, new BackgroundRunTool(backgroundManager));
        register(registry, dispatcher, new CheckTaskTool(agentTaskManager, backgroundManager));
        register(registry, dispatcher, new StopTaskTool(agentTaskManager, backgroundManager));
        register(registry, dispatcher, new MemoryTool(memoryService));

        // 上下文、记忆和压缩服务共享同一份会话工具元数据与持久记忆目录。
        TodoManager todoManager = new TodoManager(eventSink::emit);
        register(registry, dispatcher, new TodoWriteTool(todoManager));
        AutoCompactConfig compactConfig = AutoCompactConfig.from(config);
        ContextBuilder contextBuilder = new ContextBuilder(
                registry.getAllSpecs(),
                registry.getDescriptions(),
                config,
                memoryContextBuilder,
                compactConfig
        );
        SessionCheckpointState checkpointState = new SessionCheckpointState();
        SessionSummaryConfig sessionSummaryConfig = SessionSummaryConfig.defaults();
        if (sessionSummaryConfig.maxInputTokens() + sessionSummaryConfig.maxSummaryTokens()
                > compactConfig.effectiveWindow()
                || LlmSummaryCompactor.DEFAULT_MAX_CHUNK_INPUT_TOKENS
                + LlmSummaryCompactor.DEFAULT_MAX_OUTPUT_TOKENS > compactConfig.effectiveWindow()) {
            throw new IllegalArgumentException("compaction summary request exceeds the effective context window");
        }
        SessionSummaryCoordinator sessionSummaryCoordinator = new SessionSummaryCoordinator(
                new SessionSummaryGenerator(ai, new ConversationChunker(), sessionSummaryConfig),
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
                        config.getMemoryExtractionMaxRounds()
                )
                : null;
        // AgentLoop 与 ChatLoop 共享模型和 transcript，但保持不同的执行策略和历史副本。
        AgentLoop agentLoop = new AgentLoop(
                ai,
                dispatcher,
                contextBuilder,
                backgroundManager,
                confirmation,
                permissionContextStore,
                todoManager,
                compactConfig,
                config.getMaxRounds(),
                agentTaskManager,
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
     * 将工具同时注册到模型工具目录和执行分发器，保持可见性与可执行性一致。
     */
    private static void register(ToolRegistry registry, ToolDispatcher dispatcher, BaseTool tool) {
        registry.register(tool);
        dispatcher.register(tool);
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
