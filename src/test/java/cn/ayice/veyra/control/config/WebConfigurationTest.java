package cn.ayice.veyra.control.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WebConfigurationTest {

    @Test
    void disablesAsyncTimeoutForLongLivedEventStreams() {
        InspectableAsyncSupportConfigurer configurer = new InspectableAsyncSupportConfigurer();

        new WebConfiguration().configureAsyncSupport(configurer);

        assertEquals(0L, configurer.timeout());
    }

    private static final class InspectableAsyncSupportConfigurer extends AsyncSupportConfigurer {
        private Long timeout() {
            return getTimeout();
        }
    }
}
