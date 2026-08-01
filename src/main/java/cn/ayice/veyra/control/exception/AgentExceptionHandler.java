package cn.ayice.veyra.control.exception;

import cn.ayice.veyra.control.dto.common.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Veyra 本地 API 的统一异常出口。避免 Controller 里散落 try-catch。
 */
@RestControllerAdvice
public class AgentExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentExceptionHandler.class);

    /**
     * 按业务异常声明的 HTTP 状态和错误码返回失败响应。
     */
    @ExceptionHandler(AgentApiException.class)
    public ResponseEntity<ApiResponse<Object>> handleAgentApiException(AgentApiException e) {
        return ResponseEntity.status(e.status()).body(ApiResponse.failure(e.code(), safeMessage(e)));
    }

    /**
     * 将请求参数错误转换为统一的 A0400 响应。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Object>> handleIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.failure("A0400", safeMessage(e)));
    }

    /**
     * 将不存在的静态或接口资源转换为 A0404 响应。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.failure("A0404", "not found"));
    }

    /**
     * 消化异步流超时或关闭信号，避免尝试向 SSE 响应再次写入 JSON。
     */
    @ExceptionHandler({AsyncRequestTimeoutException.class, AsyncRequestNotUsableException.class})
    public void handleClosedAsyncStream(Exception e) {
        log.debug("Async HTTP stream closed: {}", e.getClass().getSimpleName());
    }

    /**
     * 记录未预期异常的完整堆栈，并向前端返回不泄露内部信息的 B0001。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Object>> handleException(Exception e) {
        log.error("Veyra HTTP request failed unexpectedly", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.failure("B0001", "系统执行失败"));
    }

    /**
     * 返回业务异常可展示消息，空消息使用稳定兜底文本。
     */
    private static String safeMessage(Exception e) {
        if (e == null || e.getMessage() == null || e.getMessage().isBlank()) {
            return "请求处理失败";
        }
        return e.getMessage();
    }

}
