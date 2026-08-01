package cn.ayice.veyra.control.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * HTTP 请求日志。记录协议边界的信息，便于从前端日志控制台反查请求是否进入后端。
 */
public class AgentRequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AgentRequestLoggingFilter.class);

    /**
     * {@inheritDoc}
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        response.addHeader("X-Request-Id", requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long elapsedMs = System.currentTimeMillis() - start;
            log.info("http requestId={} method={} path={} status={} elapsedMs={}",
                    requestId,
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    elapsedMs);
            MDC.remove("requestId");
        }
    }
}
