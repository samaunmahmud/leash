package com.leash.llm;

import com.leash.config.LeashProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.Consumer;

/**
 * Any OpenAI-compatible chat completions endpoint (Gemini, OpenAI, Groq, OpenRouter, a local Ollama...), so Leash
 * isn't tied to one provider. {@code LEASH_LLM_BASE_URL} is the API root and this posts to
 * {@code {base}/chat/completions}. For Gemini the root is {@code https://generativelanguage.googleapis.com/v1beta/openai/}
 * with the AI Studio key as the Bearer token (https://ai.google.dev/gemini-api/docs/openai).
 *
 * <p>Request: model, messages[{role, content}], temperature, response_format {type: json_object}, and
 * reasoning_effort when LEASH_LLM_REASONING_EFFORT is set.
 * Response: choices[0].message.content, usage.{prompt_tokens, completion_tokens, total_tokens}.
 * Timeouts are short because a risk check runs while the purchase waits; if the model is slow, heuristics decide alone.
 * Every call reports its latency and token use (never content or keys) to {@code onCall}.
 */
public final class OpenAiCompatibleTransport implements LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleTransport.class);
    private static final org.springframework.core.ParameterizedTypeReference<Map<String, Object>> MAP =
            new org.springframework.core.ParameterizedTypeReference<>() {};

    /**
     * One model call. Token counts are null when the provider doesn't report them. Gemini's total also counts hidden
     * "thinking" tokens, so it can be larger than prompt + completion.
     *
     * @param status HTTP status, or 0 when the call never got an answer (timeout, connection refused)
     */
    public record CallStats(String model, int status, long millis, Integer promptTokens, Integer completionTokens,
                            Integer totalTokens) {}

    public static final Consumer<CallStats> LOG = s -> log.info(
            "LLM call model={} status={} {}ms tokens prompt={} completion={} total={}",
            s.model(), s.status(), s.millis(), s.promptTokens(), s.completionTokens(), s.totalTokens());

    private final RestClient http;
    private final String model;
    private final String reasoningEffort;
    private final Consumer<CallStats> onCall;

    public OpenAiCompatibleTransport(LeashProperties.Llm cfg) {
        this(cfg, RestClient.builder(), Duration.ofSeconds(3), Duration.ofSeconds(12), LOG);
    }

    public OpenAiCompatibleTransport(LeashProperties.Llm cfg, Consumer<CallStats> onCall) {
        this(cfg, RestClient.builder(), Duration.ofSeconds(3), Duration.ofSeconds(12), onCall);
    }

    OpenAiCompatibleTransport(LeashProperties.Llm cfg, RestClient.Builder builder, Duration connect, Duration read) {
        this(cfg, builder, connect, read, LOG);
    }

    OpenAiCompatibleTransport(LeashProperties.Llm cfg, RestClient.Builder builder, Duration connect, Duration read,
                              Consumer<CallStats> onCall) {
        if (!cfg.configured()) throw new IllegalStateException("LLM isn't configured");
        String base = cfg.baseUrl().strip();
        if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connect).build());
        factory.setReadTimeout(read);
        this.http = builder.baseUrl(base)
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.apiKey().strip())
                .build();
        this.model = cfg.model().strip();
        this.reasoningEffort = cfg.reasoningEffort();
        this.onCall = onCall;
    }

    @Override
    public String chat(String system, String user) {
        Map<String, Object> body = body(system, user);
        long start = System.nanoTime();
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
            report(e.getStatusCode().value(), start, null);
            throw new LlmException("model endpoint answered " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            report(0, start, null);
            throw new LlmException("model endpoint unreachable or timed out", e);
        }
        report(200, start, res);
        return content(res);
    }

    /** The request body. reasoning_effort only when configured (Gemini: https://ai.google.dev/gemini-api/docs/openai#thinking). */
    Map<String, Object> body(String system, String user) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("model", model);
        body.put("temperature", 0);
        body.put("response_format", Map.of("type", "json_object"));
        if (reasoningEffort != null) body.put("reasoning_effort", reasoningEffort);
        body.put("messages", List.of(
                Map.of("role", "system", "content", system),
                Map.of("role", "user", "content", user)));
        return body;
    }

    private void report(int status, long startNanos, Map<String, Object> res) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000;
        Integer p = null, c = null, t = null;
        if (res != null && res.get("usage") instanceof Map<?, ?> u) {
            p = asInt(u.get("prompt_tokens"));
            c = asInt(u.get("completion_tokens"));
            t = asInt(u.get("total_tokens"));
        }
        try {
            onCall.accept(new CallStats(model, status, ms, p, c, t));
        } catch (RuntimeException ignored) {
            // stats are best-effort: never let them break a purchase
        }
    }

    private static Integer asInt(Object o) {
        return o instanceof Number n ? n.intValue() : null;
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
