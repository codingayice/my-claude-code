package cn.ayice.veyra.host.log;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * 把 SLF4J/Logback 的最终格式化文本复制一份到前端日志总线。
 */
public class Slf4jLogAppender extends AppenderBase<ILoggingEvent> {

    private static final String DEFAULT_PATTERN = "%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n";

    private String pattern = DEFAULT_PATTERN;
    private PatternLayout layout;

    /**
     * 将匹配模式更新为给定值。
     */
    public void setPattern(String pattern) {
        if (pattern != null && !pattern.isBlank()) {
            this.pattern = pattern;
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
        layout = new PatternLayout();
        layout.setContext(getContext());
        layout.setPattern(pattern);
        layout.start();
        super.start();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void append(ILoggingEvent eventObject) {
        if (layout == null) {
            return;
        }
        AgentLogBus.global().publish(layout.doLayout(eventObject));
    }
}
