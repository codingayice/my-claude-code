package cn.ayice.veyra.control.config;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Veyra 本地 HTTP 服务的 Web 层配置。
 */
@Configuration
public class WebConfiguration implements WebMvcConfigurer {

    /**
     * {@inheritDoc}
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        // Servlet timeout 0 keeps SSE streams alive; disconnects are detected by writes and heartbeats.
        configurer.setDefaultTimeout(0L);
    }

    /**
     * 创建注入 requestId 并记录耗时的 HTTP 日志过滤器。
     */
    @Bean
    public FilterRegistrationBean<AgentRequestLoggingFilter> agentRequestLoggingFilter() {
        FilterRegistrationBean<AgentRequestLoggingFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new AgentRequestLoggingFilter());
        registration.addUrlPatterns("/v1/*");
        registration.setAsyncSupported(true);
        registration.setOrder(1);
        return registration;
    }

    /**
     * 创建仅允许本地 Tauri 客户端访问的 CORS 配置。
     */
    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/v1/**")
                        .allowedOrigins("*")
                        .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                        .allowedHeaders("Content-Type", "Last-Event-ID");
            }
        };
    }
}
