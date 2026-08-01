package cn.ayice.veyra.control.exception;

import org.springframework.http.HttpStatus;

/**
 * HTTP API 可预期业务异常，用明确状态码返回给前端。
 */
public class AgentApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public AgentApiException(HttpStatus status, String message) {
        this(status, defaultCode(status), message, null);
    }

    public AgentApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /**
     * 返回当前异常或任务记录的状态。
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * 返回当前异常携带的稳定错误码。
     */
    public String code() {
        return code;
    }

    /**
     * 根据 HTTP 状态选择默认业务错误码。
     */
    private static String defaultCode(HttpStatus status) {
        if (status == HttpStatus.NOT_FOUND) {
            return "A0404";
        }
        if (status != null && status.is4xxClientError()) {
            return "A0400";
        }
        return "B0001";
    }
}
