package cn.ayice.veyra.boot;

import cn.ayice.veyra.config.AppConfig;
import cn.ayice.veyra.host.RuntimeHost;
import cn.ayice.veyra.host.SessionRegistry;
import cn.ayice.veyra.kernel.RunCoordinator;
import cn.ayice.veyra.control.document.DocumentExportService;
import cn.ayice.veyra.conversation.transcript.SessionPathResolver;
import cn.ayice.veyra.conversation.transcript.TranscriptRestorer;
import cn.ayice.veyra.conversation.transcript.TranscriptStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Spring composition root for Veyra runtime ownership and infrastructure lifecycle.
 */
@Configuration
public class RuntimeConfiguration {

    /**
     * 读取指定配置文件并创建全局 Veyra 配置 Bean。
     */
    @Bean
    public AppConfig appConfig(@Value("${veyra.config.path:}") String configPath) {
        return new AppConfig(configPath == null || configPath.isBlank() ? null : configPath);
    }

    /**
     * 创建处理会话 Run 的有界共享线程池。
     */
    @Bean(name = "agentRunExecutor", destroyMethod = "shutdown")
    public ExecutorService agentRunExecutor() {
        return managedExecutor("veyra-run-", 16, 512);
    }

    /**
     * 创建处理子 Agent 任务的有界共享线程池。
     */
    @Bean(name = "agentTaskExecutor", destroyMethod = "shutdown")
    public ExecutorService agentTaskExecutor() {
        return managedExecutor("veyra-task-", 32, 512);
    }

    /**
     * 创建处理工具 I/O 和生命周期任务的有界共享线程池。
     */
    @Bean(name = "agentIoExecutor", destroyMethod = "shutdown")
    public ExecutorService agentIoExecutor() {
        return managedExecutor("veyra-io-", 32, 1024);
    }

    /**
     * 创建基于项目隔离路径的 JSONL 转录存储。
     */
    @Bean
    public TranscriptStore transcriptStore(AppConfig config) {
        return new TranscriptStore(new SessionPathResolver(config.getMemoryDir(), config.getWorkspace()));
    }

    /**
     * 创建将持久化条目恢复为模型消息的恢复器。
     */
    @Bean
    public TranscriptRestorer transcriptRestorer() {
        return new TranscriptRestorer();
    }

    /**
     * 创建共享模型、记忆和受管线程池的会话运行时工厂。
     */
    @Bean
    public SessionRuntimeFactory sessionRuntimeFactory(
            AppConfig config,
            TranscriptStore transcriptStore,
            @Qualifier("agentRunExecutor") ExecutorService runExecutor,
            @Qualifier("agentTaskExecutor") ExecutorService taskExecutor,
            @Qualifier("agentIoExecutor") ExecutorService ioExecutor
    ) {
        return new SessionRuntimeFactory(config, transcriptStore, runExecutor, taskExecutor, ioExecutor);
    }

    /**
     * 创建负责活动会话恢复和关闭的注册表。
     */
    @Bean(destroyMethod = "close")
    public SessionRegistry sessionRegistry(
            TranscriptStore transcriptStore,
            TranscriptRestorer transcriptRestorer,
            SessionRuntimeFactory runtimeFactory
    ) {
        return new SessionRegistry(transcriptStore, transcriptRestorer, runtimeFactory);
    }

    /**
     * 创建 Agent 与 Chat 共用的 Run 生命周期协调器。
     */
    @Bean
    public RunCoordinator runCoordinator() {
        return new RunCoordinator();
    }

    /**
     * 创建控制面访问活动运行时的唯一入口。
     */
    @Bean
    public RuntimeHost runtimeHost(SessionRegistry sessions, RunCoordinator runs) {
        return new RuntimeHost(sessions, runs);
    }

    /**
     * 创建 Word 文档导出服务。
     */
    @Bean
    public DocumentExportService documentExportService() {
        return new DocumentExportService();
    }

    /**
     * 创建具有固定线程数、有界队列和统一命名规则的受管线程池。
     */
    private static ExecutorService managedExecutor(String prefix, int threads, int queueCapacity) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(
                threads,
                threads,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
                    thread.setDaemon(false);
                    return thread;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
