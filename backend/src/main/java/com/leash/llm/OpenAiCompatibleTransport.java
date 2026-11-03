package com.leash.llm;

import com.leash.config.LeashProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Any OpenAI-compatible chat completions endpoint (OpenAI, Groq, OpenRouter, Together, a local Ollama...), so Leash
 * isn't tied to one provider. {@code LEASH_LLM_BASE_URL} is the API root, e.g. {@code https://api.openai.com/v1};
 * this posts to {@code {base}/chat/completions}.
 *
 * <p>Request: model, messages[{role, content}], temperature, response_format {type: json_object}.
 * Response: choices[0].message.content. Timeouts are short because a risk check runs while the purchase waits; if the
 * model is slow, heuristics decide alone.
 */
public final class OpenAiCompatibleTransport implements LlmTransport {

    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> MAP =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final String model;

    public OpenAiCompatibleTransport(LeashProperties.Llm cfg) {
        this(cfg, RestClient.builder(), Duration.ofSeconds(3), Duration.ofSeconds(12));
    }

    OpenAiCompatibleTransport(LeashProperties.Llm cfg, RestClient.Builder builder, Duration connect, Duration read) {
        if (!cfg.configured()) throw new IllegalStateException("LLM isn't configured");
        String base = cfg.baseUrl().endsWith("/") ? cfg.baseUrl().substring(0, cfg.baseUrl().length() - 1) : cfg.baseUrl();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        this.http = builder.baseUrl(base)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey())
                .build();
        this.model = cfg.model();
    }

    @Override
    public String chat(String system, String user) {
        Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", user)));
        Map<String, Object> res;
        try {
            res = http.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(MAP);
        } catch (RestClientResponseException e) {
            throw new LlmException("model endpoint answered " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            throw new LlmException("model endpoint unreachable or timed out", e);
        }
        return content(res);
    }

    static String content(Map<String, Object> res) {
        if (res != null && res.get("choices") instanceof List<?> choices && !choices.isEmpty()
                && choices.getFirst() instanceof Map<?, ?> choice
                && choice.get("message") instanceof Map<?, ?> message
                && message.get("content") instanceof String text) {
            return text;
        }
        throw new LlmException("model answered without choices[0].message.content");
    }
}
