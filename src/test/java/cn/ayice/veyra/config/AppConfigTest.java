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
    void allDefaultPersistencePathsShareVeyraRoot() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                app:
                  name: Test
                """);

        AppConfig appConfig = new AppConfig(config.toString());

        assertEquals("~/.veyra", appConfig.getStorageRoot());
        assertEquals("~/.veyra/sessions", appConfig.getMemoryDir());
        assertEquals("~/.veyra/memory", appConfig.getLongTermMemoryDir());
    }

    @Test
    void derivesPersistencePathsFromConfiguredStorageRoot() throws Exception {
        Path config = tempDir.resolve("custom-config.yaml");
        Files.writeString(config, """
                storage:
                  root: D:/veyra-data
                """);

        AppConfig appConfig = new AppConfig(config.toString());

        assertEquals("D:/veyra-data", appConfig.getStorageRoot());
        assertEquals("D:/veyra-data/sessions", appConfig.getMemoryDir());
        assertEquals("D:/veyra-data/memory", appConfig.getLongTermMemoryDir());
    }
}
