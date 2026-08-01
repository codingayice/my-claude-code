package cn.ayice.veyra.control.api;

import cn.ayice.veyra.session.event.AgentEvent;
import cn.ayice.veyra.session.log.AgentLogBus;
import cn.ayice.veyra.session.log.AgentLogLine;
import cn.ayice.veyra.control.sse.StreamingAgentEventSubscriber;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后端 SLF4J 日志 SSE。前端日志控制台直接显示这里推送的原始日志行。
 */
@RestController
@RequestMapping("/v1")
public class AgentLogController {

    private static final Logger log = LoggerFactory.getLogger(AgentLogController.class);

    /**
     * 建立后端日志 SSE 流并持续写出日志事件。
     */
    @GetMapping("/logs/events")
    public ResponseEntity<StreamingResponseBody> logs() {
        StreamingResponseBody body = output -> {
            var subscriber = new StreamingAgentEventSubscriber(output);
            AutoCloseable subscription = null;
            try {
                subscriber.send(AgentEvent.of(0, "", null, "log.ready", Map.of()));
                subscription = AgentLogBus.global().subscribe(line -> sendLogLine(subscriber, line), true);
                subscriber.awaitCloseWithHeartbeat();
            } catch (IOException e) {
                log.debug("Log SSE client disconnected", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("log SSE stream interrupted", e);
            } finally {
                closeQuietly(subscription);
                subscriber.close();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }

    /**
     * 处理并传播 {@code sendLogLine} 对应的事件。
     */
    private static void sendLogLine(StreamingAgentEventSubscriber subscriber, AgentLogLine line) {
        try {
            AgentEvent event = AgentEvent.of(line.seq(), "", null, "log.line", Map.of("line", line.line()));
            subscriber.send(event);
        } catch (IOException e) {
            log.debug("Log SSE client disconnected", e);
            subscriber.close();
        }
    }

    /**
     * 终止 {@code closeQuietly} 对应的运行资源。
     */
    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception closeError) {
            log.debug("Failed to close log SSE subscription", closeError);
        }
    }
}
