package cn.ayice.veyra.control.sse;

import cn.ayice.veyra.host.event.AgentEvent;
import cn.ayice.veyra.host.event.AgentEventSubscriber;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Spring StreamingResponseBody 使用的 SSE 写出器。它显式写出 event/data 帧，保证前端 EventSource 看到的格式稳定。
 */
public class StreamingAgentEventSubscriber implements AgentEventSubscriber {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] HEARTBEAT = ": keep-alive\n\n".getBytes(StandardCharsets.UTF_8);

    private final OutputStream output;
    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private volatile boolean closed;

    /**
     * 使用当前 HTTP 响应输出流创建 SSE 订阅者。
     */
    public StreamingAgentEventSubscriber(OutputStream output) {
        this.output = output;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void send(AgentEvent event) throws IOException {
        if (closed) {
            throw new IOException("SSE subscriber closed");
        }
        String payload = "id: " + event.seq() + "\n"
                + "event: " + event.type() + "\n"
                + "data: " + MAPPER.writeValueAsString(event) + "\n\n";
        output.write(payload.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * 阻塞保持连接，并每秒写出心跳以发现断开的客户端和维持代理连接。
     */
    public void awaitCloseWithHeartbeat() throws InterruptedException, IOException {
        while (!closed && !closeLatch.await(1, TimeUnit.SECONDS)) {
            writeHeartbeat();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeLatch.countDown();
    }

    /**
     * 以同步方式写出一个 SSE 注释帧，避免与业务事件帧交叉。
     */
    private synchronized void writeHeartbeat() throws IOException {
        if (closed) {
            return;
        }
        output.write(HEARTBEAT);
        output.flush();
    }
}
