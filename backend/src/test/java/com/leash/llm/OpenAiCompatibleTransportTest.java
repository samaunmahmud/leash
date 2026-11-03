package com.leash.llm;

import com.leash.config.LeashProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a real local HTTP server standing in for an OpenAI-compatible endpoint. */
class OpenAiCompatibleTransportTest {

    HttpServer server;
    final AtomicReference<String> path = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    final AtomicReference<JsonNode> body = new AtomicReference<>();

    void start(int status, String response, long delayMs) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", ex -> {
            path.set(ex.getRequestURI().getPath());
            auth.set(ex.getRequestHeaders().getFirst("Authorization"));
            body.set(JsonMapper.builder().build().readTree(ex.getRequestBody().readAllBytes()));
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    OpenAiCompatibleTransport transport(Duration read) {
        var cfg = new LeashProperties.Llm("http://localhost:" + server.getAddress().getPort() + "/v1/", "test-key",
                "test-model", 10);
        return new OpenAiCompatibleTransport(cfg, RestClient.builder(), Duration.ofSeconds(2), read);
    }

    @Test
    void postsChatCompletionsAndReturnsTheContent() throws IOException {
        start(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"risk\\\":\\\"LOW\\\"}\"}}]}", 0);
        String out = transport(Duration.ofSeconds(5)).chat("sys", "usr");

        assertThat(out).isEqualTo("{\"risk\":\"LOW\"}");
        assertThat(path.get()).isEqualTo("/v1/chat/completions");
        assertThat(auth.get()).isEqualTo("Bearer test-key");
        assertThat(body.get().get("model").asString()).isEqualTo("test-model");
        assertThat(body.get().get("response_format").get("type").asString()).isEqualTo("json_object");
        assertThat(body.get().get("messages").get(0).get("role").asString()).isEqualTo("system");
        assertThat(body.get().get("messages").get(1).get("content").asString()).isEqualTo("usr");
    }

    @Test
    void errorStatusBecomesLlmExceptionWithoutTheBody() throws IOException {
        start(401, "{\"error\":{\"message\":\"Incorrect API key provided: test-key\"}}", 0);
        assertThatThrownBy(() -> transport(Duration.ofSeconds(5)).chat("s", "u"))
                .isInstanceOf(LlmException.class)
                .hasMessage("model endpoint answered 401");
    }

    @Test
    void slowModelTimesOut() throws IOException {
        start(200, "{}", 1_500);
        assertThatThrownBy(() -> transport(Duration.ofMillis(200)).chat("s", "u"))
                .isInstanceOf(LlmException.class)
                .hasMessageContaining("timed out");
    }
}
