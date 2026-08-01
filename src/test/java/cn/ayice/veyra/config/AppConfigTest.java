package cn.ayice.veyra.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppConfigTest {

    @TempDir
    Path tempDir;

    @Test
    void defaultMemoryRootIsMyccRootNotNestedMemoryDirectory() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                app:
                  name: Test
                """);

        AppConfig appConfig = new AppConfig(config.toString());

        assertEquals("~/.mycc", appConfig.getMemoryDir());
    }
}
