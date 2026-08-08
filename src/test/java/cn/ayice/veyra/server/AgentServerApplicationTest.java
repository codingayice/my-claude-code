package cn.ayice.veyra.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentServerApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void servesJsonEndpointsAndSessionEventsThroughSpringBoot() throws Exception {
        try (ConfigurableApplicationContext context = startServer()) {
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            String base = "http://127.0.0.1:" + port + "/v1";

            HttpResult health = get(base + "/health");
            assertEquals(200, health.status());
            assertTrue(health.body().contains("\"success\":true"));
            assertTrue(health.body().contains("\"code\":\"00000\""));
            assertTrue(health.body().contains("\"ok\":true"));

            HttpResult created = postJson(base + "/sessions", "");
            assertEquals(200, created.status(), created.body());
            String sessionId = extractJsonString(created.body(), "sessionId");

            HttpResult session = get(base + "/sessions/" + sessionId);
            assertEquals(200, session.status(), session.body());
            assertTrue(session.body().contains("\"workingDir\""));

            HttpResult sessions = get(base + "/sessions");
            assertEquals(200, sessions.status(), sessions.body());
            assertTrue(sessions.body().contains("\"items\""));

            HttpResult transcript = get(base + "/sessions/" + sessionId + "/transcript");
            assertEquals(200, transcript.status(), transcript.body());
            assertTrue(transcript.body().contains("\"items\""));

            String firstEventLine = firstEventLine(base + "/sessions/" + sessionId + "/events");
            assertEquals("event: session.ready", firstEventLine);

            String firstLogLine = firstEventLine(base + "/logs/events");
            assertEquals("event: log.ready", firstLogLine);

            HttpResult slashCommands = get(base + "/sessions/" + sessionId + "/slash-commands?query=");
            assertEquals(200, slashCommands.status(), slashCommands.body());
            assertTrue(slashCommands.body().contains("\"items\""));

            HttpResult approvals = get(base + "/sessions/" + sessionId + "/approvals");
            assertEquals(200, approvals.status(), approvals.body());
            assertTrue(approvals.body().contains("\"items\""));

            HttpResult emptyRun = postJson(base + "/sessions/" + sessionId + "/runs", "{\"input\":\"\",\"mode\":\"agent\"}");
            assertEquals(202, emptyRun.status(), emptyRun.body());
            assertTrue(emptyRun.body().contains("\"accepted\":false"));

            HttpResult oldMessages = postJson(base + "/sessions/" + sessionId + "/messages", "{\"text\":\"hello\"}");
            assertEquals(404, oldMessages.status(), oldMessages.body());
        }
    }

    @Test
    void mapsJsonApiToAgentControllerAndKeepsStreamingControllersSeparate() throws Exception {
        try (ConfigurableApplicationContext context = startServer()) {
            assertMappedTo(context, "/v1/health", "AgentController");
            assertMappedTo(context, "/v1/sessions", "AgentController");
            assertMappedTo(context, "/v1/sessions/{sessionId}/runs", "AgentController");
            assertMappedTo(context, "/v1/sessions/{sessionId}/approvals", "AgentController");
            assertMappedTo(context, "/v1/sessions/{sessionId}/slash-commands", "AgentController");
            assertMappedTo(context, "/v1/sessions/{sessionId}/events", "AgentEventController");
            assertMappedTo(context, "/v1/logs/events", "AgentLogController");
            assertMappedTo(context, "/v1/documents/word-exports", "DocumentController");
        }
    }

    private ConfigurableApplicationContext startServer() throws Exception {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                model:
                  name: fake
                  baseUrl: http://localhost
                  apiKey: test-key
                  maxTokens: 128
                  timeoutSeconds: 1
                context:
                  maxContextTokens: 128000
                storage:
                  root: %s
                security:
                  workspace: %s
                permission:
                  mode: ask_every_time
                """.formatted(
                tempDir.resolve("memory").toString().replace("\\", "\\\\"),
                tempDir.toString().replace("\\", "\\\\")
        ));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "0");
        properties.put("veyra.config.path", config.toString());
        return new SpringApplicationBuilder(AgentServerApplication.class)
                .properties(properties)
                .run();
    }

    private static HttpResult get(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        return read(connection);
    }

    private static HttpResult postJson(String url, String body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);
        try (OutputStream os = connection.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(connection);
    }

    private static HttpResult read(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                status >= 400 ? connection.getErrorStream() : connection.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String body = reader.lines().reduce("", (left, right) -> left + right);
            return new HttpResult(status, body);
        }
    }

    private static String firstEventLine(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(5_000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("event: ")) {
                    return line;
                }
            }
        }
        return "";
    }

    private static String extractJsonString(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            return "";
        }
        int valueStart = start + marker.length();
        int valueEnd = json.indexOf('"', valueStart);
        return json.substring(valueStart, valueEnd);
    }

    private record HttpResult(int status, String body) {
    }

    private static void assertMappedTo(ConfigurableApplicationContext context, String path, String controllerName) {
        RequestMappingHandlerMapping mappings = context.getBean(RequestMappingHandlerMapping.class);
        String actual = mappings.getHandlerMethods().entrySet().stream()
                .filter(entry -> matchesPath(entry.getKey(), path))
                .map(Map.Entry::getValue)
                .map(HandlerMethod::getBeanType)
                .map(Class::getSimpleName)
                .findFirst()
                .orElse("");
        assertEquals(controllerName, actual, path);
    }

    private static boolean matchesPath(RequestMappingInfo info, String path) {
        if (info.getPathPatternsCondition() == null) {
            return false;
        }
        return info.getPathPatternsCondition().getPatternValues().contains(path);
    }
}
