package cn.ayice;

import cn.ayice.veyra.server.AgentServerApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import picocli.CommandLine;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;

@CommandLine.Command(
        name = "agent",
        mixinStandardHelpOptions = true,
        version = "1.0",
        description = "Java agent 运行时"
)
public class Main implements Callable<Integer> {

    @CommandLine.Option(names = {"-c", "--config"})
    String configPath;

    @CommandLine.Option(names = {"--port"})
    int port = 17361;

    @CommandLine.Option(names = {"--http"}, hidden = true)
    boolean http;

    public static void main(String[] args) {
        requireUtf8Runtime();
        int exitCode = new CommandLine(new Main()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public Integer call() throws InterruptedException {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", Integer.toString(port));
        if (configPath != null && !configPath.isBlank()) {
            properties.put("veyra.config.path", configPath);
        }

        new SpringApplicationBuilder(AgentServerApplication.class)
                .properties(properties)
                .run();
        System.out.println("HTTP 服务已启动: http://127.0.0.1:" + port);
        Thread.currentThread().join();
        return 0;
    }

    static void requireUtf8Runtime() {
        Charset charset = Charset.defaultCharset();
        if (!isUtf8Runtime(charset)) {
            throw new IllegalStateException(
                    "Veyra 后端必须使用 UTF-8 运行，请用 -Dfile.encoding=UTF-8 启动。当前编码: " + charset
            );
        }
    }

    static boolean isUtf8Runtime(Charset charset) {
        return StandardCharsets.UTF_8.equals(charset);
    }
}
