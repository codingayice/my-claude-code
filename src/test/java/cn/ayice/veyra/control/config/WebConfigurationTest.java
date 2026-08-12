package cn.ayice.veyra.control.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebConfigurationTest {

    @Test
    void disablesAsyncTimeoutForLongLivedEventStreams() {
        InspectableAsyncSupportConfigurer configurer = new InspectableAsyncSupportConfigurer();

        new WebConfiguration().configureAsyncSupport(configurer);

        assertEquals(0L, configurer.timeout());
    }

    @Test
    void allowsDeleteRequestsFromTheDesktopClient() {
        InspectableCorsRegistry registry = new InspectableCorsRegistry();

        new WebConfiguration().corsConfigurer().addCorsMappings(registry);

        CorsConfiguration configuration = registry.configurations().get("/v1/**");
        assertTrue(configuration.getAllowedMethods().contains("DELETE"));
    }

    private static final class InspectableAsyncSupportConfigurer extends AsyncSupportConfigurer {
        private Long timeout() {
            return getTimeout();
        }
    }

    private static final class InspectableCorsRegistry extends CorsRegistry {
        private Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
