package com.leash.llm;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Asks a model for one JSON object, checks it, and turns it into something Leash can use. The pattern every AI call in
 * Leash goes through:
 * <ol>
 *   <li>spend guard ({@link LlmBudget}): no allowance left → no call</li>
 *   <li>parse the reply as JSON into {@code type}</li>
 *   <li>Bean Validation on the result, then {@code convert} (which may reject it with an IllegalArgumentException)</li>
 *   <li>anything wrong → one retry that tells the model what was wrong</li>
 *   <li>still wrong, or the model is unreachable → {@link Optional#empty()}, and the caller falls back to heuristics</li>
 *   <li>circuit breaker: after the model is unreachable, errors or times out, skip it for {@link #COOL_OFF} so every
 *       following purchase doesn't wait out the timeout too (seen live: Gemini stalled and a 3-step demo took 36 s)</li>
 * </ol>
 * Never throws for model trouble. Never logs prompts, replies or keys: only which step failed.
 */
public final class StructuredLlm {

    private static final Logger log = LoggerFactory.getLogger(StructuredLlm.class);

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            // Models sometimes send "notes": "one note" for a list. Reading it as a one-item list loses nothing:
            // the value is still validated like any other.
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .build();

    public static final java.time.Duration COOL_OFF = java.time.Duration.ofSeconds(60);

    private final LlmTransport transport;
    private final LlmBudget budget;
    private final Validator validator;
    private final java.time.Clock clock;
    private volatile java.time.Instant skipUntil = java.time.Instant.EPOCH;

    /** @param transport null when no model is configured: every ask then returns empty without trying */
    public StructuredLlm(LlmTransport transport, LlmBudget budget, Validator validator) {
        this(transport, budget, validator, java.time.Clock.systemUTC());
    }

    public StructuredLlm(LlmTransport transport, LlmBudget budget, Validator validator, java.time.Clock clock) {
        this.transport = transport;
        this.budget = budget;
        this.validator = validator;
        this.clock = clock;
    }

    public boolean enabled() {
        return transport != null;
    }

    public <T, R> Optional<R> ask(String purpose, String system, String user, Class<T> type, Function<T, R> convert) {
        if (!enabled()) return Optional.empty();
        if (clock.instant().isBefore(skipUntil)) {
            log.debug("LLM {}: skipped, model failed recently", purpose);
            return Optional.empty();
        }
        String prompt = user;
        for (int attempt = 1; attempt <= 2; attempt++) {
            if (!budget.tryAcquire()) {
                log.warn("LLM {}: daily call limit reached, using heuristics", purpose);
                return Optional.empty();
            }
            String reply;
            try {
                reply = transport.chat(system, prompt);
            } catch (RuntimeException e) { // LlmException, or anything a transport throws
                skipUntil = clock.instant().plus(COOL_OFF);
                log.warn("LLM {}: {} (using heuristics only for the next {}s)", purpose, e.getMessage(), COOL_OFF.toSeconds());
                return Optional.empty(); // unreachable or erroring: retrying now rarely helps and delays the purchase
            }
            String problem;
            try {
                T value = JSON.readValue(stripFences(reply), type);
                if (value == null) throw new IllegalArgumentException("the reply was empty");
                var violations = validator.validate(value);
                if (!violations.isEmpty()) throw new IllegalArgumentException(describe(violations));
                return Optional.of(convert.apply(value));
            } catch (JacksonException e) {
                problem = jsonProblem(e);
            } catch (IllegalArgumentException e) {
                problem = e.getMessage();
            }
            // The problem names fields and constraints only (or a generic JSON error), never the reply's content.
            log.warn("LLM {}: attempt {} rejected because {} ({})", purpose, attempt, problem,
                    attempt == 1 ? "retrying" : "giving up");
            prompt = user + "\n\nYour previous reply was rejected because " + problem
                    + ". Reply again with only the corrected JSON object.";
        }
        return Optional.empty();
    }

    /**
     * Tells the model which field was wrong so the retry can fix it, without echoing any of the reply (Jackson's own
     * message quotes the offending value).
     */
    static String jsonProblem(JacksonException e) {
        String field = e.getPath().stream()
                .map(r -> r.getPropertyName() != null ? r.getPropertyName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining(".")).replace(".[", "[");
        return field.isEmpty()
                ? "it wasn't a single valid JSON object matching the schema"
                : "the field \"" + field + "\" has the wrong type or format for the schema";
    }

    /** Some models wrap JSON in ```json fences even when asked not to. */
    static String stripFences(String s) {
        if (s == null) return "";
        String t = s.strip();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) t = t.substring(firstNewline + 1, lastFence).strip();
        }
        return t;
    }

    private static <T> String describe(java.util.Set<ConstraintViolation<T>> violations) {
        return violations.stream()
                .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .collect(Collectors.joining("; "));
    }
}
