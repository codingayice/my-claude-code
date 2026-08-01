package cn.ayice.veyra.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;


/**
 * 应用配置门面。它从 YAML 和默认值中暴露模型、工作区、服务端、压缩、记忆、权限和循环轮数等配置。
 */
public class AppConfig {

    private static final Pattern ENV_PATTERN = Pattern.compile("\\$\\{([^}]+)}");
    private final Map<String, Object> config;

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * 读取配置源并初始化 AppConfig。
     */
    public AppConfig(String configPath) {
        this.config = loadFromFile(configPath);
    }

    /**
     * 读取 YAML 配置文件并构建配置对象；文件缺失或字段非法时使用受控默认值。
     */
    private Map<String, Object> loadFromFile(String path) {
        try {
            Path p = Paths.get(path == null ? "config.yaml" : path);
            byte[] bytes;
            if(Files.exists(p)) {
                bytes = Files.readAllBytes(p);
            } else {
                // 从 classpath 加载
                try (InputStream is = getClass().getClassLoader().getResourceAsStream("config.yaml")) {
                    if (is == null) {
                        log.error("配置文件未找到: {}", path);
                        return new HashMap<>();
                    }
                    bytes = is.readAllBytes();
                }
            }
            // 解析 YAML
            return new Yaml().load(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("加载配置文件失败: {}", path, e);
            return new HashMap<>();
        }
    }

    // ====================== 取值辅助方法 ======================

    /**
     * 按配置键读取原始值；配置文件未提供时回退到环境变量或默认值。
     */
    @SuppressWarnings("unchecked")
    private<T> T get(String ... path) {
        Object current = config;
        for(String key : path) {
            if(current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
        }
        return (T) current;
    }

    /**
     * 读取字符串配置，并在缺失时使用默认值。
     */
    private String str(String section, String key, String def) {
        Map<String, Object> sec = get(section);
        if(sec == null) {
            log.error("配置块 '{}' 不存在，使用默认值: {}", section, def);
            return def;
        }
        Object val = sec.get(key);
        return val != null ? val.toString() : def;
    }

    /**
     * 读取必需整数配置，并校验其取值范围。
     */
    private int num(String section, String key, int def) {
        Map<String, Object> sec = get(section);
        if(sec == null) {
            log.error("配置块 '{}' 不存在，使用默认值: {}", section, def);
            return def;
        }
        Object val = sec.get(key);
        if(val instanceof Number) {
            return ((Number) val).intValue();
        }
        return def;
    }

    /**
     * 读取可选整数配置；未配置时返回空值。
     */
    private Integer optionalNum(String section, String key) {
        Map<String, Object> sec = get(section);
        if(sec == null) {
            return null;
        }
        Object val = sec.get(key);
        if(val instanceof Number) {
            return ((Number) val).intValue();
        }
        if (val instanceof String str && !str.isBlank()) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 读取布尔配置，并兼容字符串和布尔字面量。
     */
    private boolean bool(String section, String key, boolean def) {
        Map<String, Object> sec = get(section);
        if(sec == null) {
            log.error("配置块 '{}' 不存在，使用默认值: {}", section, def);
            return def;
        }
        Object val = sec.get(key);
        if(val instanceof Boolean) {
            return (Boolean) val;
        }
        if (val instanceof String str) {
            return Boolean.parseBoolean(str);
        }
        return def;
    }

    /**
     * 读取小数配置，并校验其取值范围。
     */
    private double dbl(String section, String key, double def) {
        Map<String, Object> sec = get(section);
        if(sec == null) {
            log.error("配置块 '{}' 不存在，使用默认值: {}", section, def);
            return def;
        }
        Object val = sec.get(key);
        if(val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        return def;
    }

    // ====================== 具体配置项访问方法 ======================

    /**
     * 返回模型请求中用于标识客户端的应用名称。
     */
    public String getAppName() {
        return str("app", "name", "Veyra");
    }

    /**
     * 返回模型名称。
     */
    public String getModelName() {
        return str("model", "name", "deepseek-chat");
    }

    /**
     * 返回系统提示词中使用的应用描述。
     */
    public String getAppDescription() {
        return str("app", "description", "一个运行在本地工作区、可协调工具与子 Agent 完成任务的智能桌面助手");
    }

    /**
     * 返回兼容 OpenAI 协议的模型服务基础地址。
     */
    public String getBaseUrl() {
        return str("model", "baseUrl", "https://api.deepseek.com/v1");
    }

    /**
     * 返回模型服务 API Key；调用方不得将其写入日志或响应。
     */
    public String getApiKey() {
        return envOr(str("model", "apiKey", ""));
    }

    /**
     * 返回模型温度。
     */
    public double getTemperature() {
        return dbl("model", "temperature", 0.7);
    }

    /**
     * 返回单次模型响应允许生成的最大 token 数。
     */
    public int getMaxTokens() {
        return num("model", "maxTokens", 4096);
    }

    /**
     * 返回等待单次模型调用完成的超时秒数。
     */
    public int getModelTimeoutSeconds() {
        return num("model", "timeoutSeconds", 120);
    }

    /**
     * 返回最大最大轮数。
     */
    public int getMaxRounds() {
        return num("context", "maxRounds", 0);
    }

    /**
     * 返回模型上下文窗口允许使用的最大 token 数。
     */
    public int getMaxContextTokens() {
        return num("context", "maxContextTokens", 128000);
    }

    /**
     * 返回是否启用达到阈值后的自动上下文压缩。
     */
    public boolean isAutoCompactEnabled() {
        return bool("context", "autoCompactEnabled", true);
    }

    /**
     * 返回是否启用仅裁剪旧工具结果的微压缩。
     */
    public boolean isMicroCompactEnabled() {
        return bool("context", "microCompactEnabled", true);
    }

    /**
     * 返回完整压缩后是否恢复必要的系统提示词片段。
     */
    public boolean isPostCompactRestoreEnabled() {
        return bool("context", "postCompactRestoreEnabled", true);
    }

    /**
     * 返回自动压缩窗口覆盖值；未配置时由模型上下文大小推导。
     */
    public Integer getAutoCompactWindowOverride() {
        Integer override = optionalNum("context", "autoCompactWindowOverride");
        return override != null && override > 0 ? override : null;
    }

    /**
     * 返回权限权限模式。
     */
    public String getPermissionMode() {
        return str("permission", "mode", "ask_every_time");
    }

    /**
     * 返回上下文危险阈值比例。
     */
    public double getContextDangerRatio() {
        return dbl("context", "contextDangerRatio", 0.9);
    }

    /**
     * 返回工作区。
     */
    public String getWorkspace() { return System.getProperty("user.dir");}

    /**
     * 返回会话记录和压缩恢复数据使用的兼容存储根目录。
     */
    public String getMemoryDir() { return str("memory", "dir", "~/.mycc");}

    /**
     * 返回跨会话长期记忆的独立存储根目录。
     */
    public String getLongTermMemoryDir() {
        return str("memory", "longTermDir", "~/.veyra/memory");
    }

    /**
     * 返回是否启用后台长期记忆自动提取。
     */
    public boolean isAutoMemoryExtractionEnabled() {
        return bool("memory", "autoExtractionEnabled", true);
    }

    /**
     * 返回单个长期记忆 topic 的最大持久化字节数。
     */
    public int getMemoryMaxTopicBytes() {
        return positiveMemoryNumber("maxTopicBytes", 16_384);
    }

    /**
     * 返回单个 MEMORY.md 索引允许的最大行数。
     */
    public int getMemoryMaxIndexLines() {
        return positiveMemoryNumber("maxIndexLines", 200);
    }

    /**
     * 返回单个 MEMORY.md 索引允许的最大字节数。
     */
    public int getMemoryMaxIndexBytes() {
        return positiveMemoryNumber("maxIndexBytes", 25_600);
    }

    /**
     * 返回单个命名空间一次扫描的最大 topic 数量。
     */
    public int getMemoryMaxScannedTopics() {
        return positiveMemoryNumber("maxScannedTopics", 200);
    }

    /**
     * 返回 ALWAYS 用户记忆单轮注入总预算。
     */
    public int getMemoryMaxAlwaysContextBytes() {
        return positiveMemoryNumber("maxAlwaysContextBytes", 4_096);
    }

    /**
     * 返回单轮最多召回的 RELEVANT 记忆数量。
     */
    public int getMemoryMaxRecallItems() {
        return positiveMemoryNumber("maxRecallItems", 5);
    }

    /**
     * 返回单条召回记忆进入上下文的最大字节数。
     */
    public int getMemoryMaxRecalledTopicBytes() {
        return positiveMemoryNumber("maxRecalledTopicBytes", 4_096);
    }

    /**
     * 返回单轮动态长期记忆上下文的总字节预算。
     */
    public int getMemoryMaxTurnContextBytes() {
        return positiveMemoryNumber("maxTurnContextBytes", 20_480);
    }

    /**
     * 返回后台提取 Subagent 的最大轮数。
     */
    public int getMemoryExtractionMaxRounds() {
        return positiveMemoryNumber("extractionMaxRounds", 5);
    }

    /**
     * 读取并验证记忆模块的正整数配置，非法配置在装配阶段直接失败。
     */
    private int positiveMemoryNumber(String key, int defaultValue) {
        int value = num("memory", key, defaultValue);
        if (value <= 0) {
            throw new IllegalArgumentException("memory." + key + " must be positive");
        }
        return value;
    }


    //======================= 环境变量插值 ======================
    /**
     * 优先读取环境变量，缺失时返回给定默认值。
     */
    private String envOr(String value) {
        Matcher matcher = ENV_PATTERN.matcher(value);
        StringBuilder sb = new StringBuilder();
        while(matcher.find()) {
            String envKey = matcher.group(1);
            String envVal = System.getenv(envKey);
            matcher.appendReplacement(sb, envVal != null ? Matcher.quoteReplacement(envVal) : "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }


}
