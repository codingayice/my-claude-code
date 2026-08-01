package cn.ayice.veyra.control.api;

import cn.ayice.veyra.host.RuntimeHost;
import cn.ayice.veyra.host.event.AgentEvent;
import cn.ayice.veyra.host.event.SessionEventStream;
import cn.ayice.veyra.control.sse.StreamingAgentEventSubscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.util.Map;

/**
 * 会话运行事件 SSE。前端通过它观察 agent 每一步做了什么。
 */
@RestController
@RequestMapping("/v1/sessions/{sessionId}")
public class AgentEventController {

    private static final Logger log = LoggerFactory.getLogger(AgentEventController.class);

    private final RuntimeHost runtimeHost;

    /**
     * 使用唯一运行时入口创建会话事件控制器。
     */
    public AgentEventController(RuntimeHost runtimeHost) {
        this.runtimeHost = runtimeHost;
    }

    /**
     * 建立指定会话的长连接 SSE 事件流，并持续发送心跳直到客户端断开。
     */
    @GetMapping("/events")
    public ResponseEntity<StreamingResponseBody> events(@PathVariable("sessionId") String sessionId) {
        SessionEventStream events = runtimeHost.events(sessionId);
        StreamingResponseBody body = output -> {
            StreamingAgentEventSubscriber subscriber = new StreamingAgentEventSubscriber(output);
            events.addSubscriber(subscriber);
            try {
                // 注册成功后先发送 ready，前端据此区分已建立连接和仍在重连。
                subscriber.send(AgentEvent.of(
                        events.nextSeq(),
                        sessionId,
                        null,
                        "session.ready",
                        Map.of("sessionId", sessionId)
                ));
                subscriber.awaitCloseWithHeartbeat();
            } catch (IOException e) {
                // 客户端关闭窗口或网络中断属于 SSE 正常生命周期，不交给 JSON 全局异常处理器。
                log.debug("Agent event SSE client disconnected, sessionId={}", sessionId, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("SSE stream interrupted", e);
            } finally {
                // 无论断开、线程中断还是写出失败，都必须移除订阅者，防止后续事件写向失效连接。
                events.removeSubscriber(subscriber);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(body);
    }
}
