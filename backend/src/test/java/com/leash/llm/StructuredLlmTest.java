package com.leash.llm;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class StructuredLlmTest {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    record Answer(@NotBlank String word, @Positive int n) {}

    /** A fake model that replies from a script and records every prompt it was sent. */
    static final class Scripted implements LlmTransport {
        final List<String> replies;
        final List<String> prompts = new ArrayList<>();

        Scripted(String... replies) {
            this.replies = List.of(replies);
        }

        @Override
        public String chat(String system, String user) {
            prompts.add(user);
            return replies.get(Math.min(prompts.size() - 1, replies.size() - 1));
        }
    }

    StructuredLlm llm(LlmTransport t, int perDay) {
        return new StructuredLlm(t, new LlmBudget(perDay, CLOCK), VALIDATOR);
    }

    java.util.Optional<Answer> ask(StructuredLlm llm) {
        return llm.ask("test", "system", "question", Answer.class, Function.identity());
    }

    @Test
    void noModelConfiguredMeansNoCallAndEmpty() {
        var llm = llm(null, 100);
        assertThat(llm.enabled()).isFalse();
        assertThat(ask(llm)).isEmpty();
    }

    @Test
    void readsValidJsonFirstTime() {
        var t = new Scripted("{\"word\":\"leash\",\"n\":3,\"extra\":true}");
        assertThat(ask(llm(t, 100))).contains(new Answer("leash", 3));
        assertThat(t.prompts).hasSize(1);
    }

    @Test
    void toleratesCodeFences() {
        var t = new Scripted("```json\n{\"word\":\"leash\",\"n\":1}\n```");
        assertThat(ask(llm(t, 100))).contains(new Answer("leash", 1));
    }

    @Test
    void retriesOnceOnInvalidJsonAndSaysWhatWasWrong() {
        var t = new Scripted("Sure! Here you go: word=leash", "{\"word\":\"leash\",\"n\":2}");
        assertThat(ask(llm(t, 100))).contains(new Answer("leash", 2));
        assertThat(t.prompts).hasSize(2);
        assertThat(t.prompts.get(1)).startsWith("question").contains("rejected", "JSON");
    }

    @Test
    void retriesOnceOnValidationFailureAndNamesTheField() {
        var t = new Scripted("{\"word\":\"leash\",\"n\":-1}", "{\"word\":\"leash\",\"n\":1}");
        assertThat(ask(llm(t, 100))).contains(new Answer("leash", 1));
        assertThat(t.prompts.get(1)).contains("n must be greater than 0");
    }

    @Test
    void retriesWhenTheConverterRejects() {
        var t = new Scripted("{\"word\":\"bad\",\"n\":1}", "{\"word\":\"good\",\"n\":1}");
        var out = llm(t, 100).ask("test", "s", "q", Answer.class, a -> {
            if (a.word().equals("bad")) throw new IllegalArgumentException("word must not be bad");
            return a.word();
        });
        assertThat(out).contains("good");
        assertThat(t.prompts.get(1)).contains("word must not be bad");
    }

    @Test
    void givesUpAfterTwoBadAnswers() {
        var t = new Scripted("nope", "still nope", "{\"word\":\"late\",\"n\":1}");
        assertThat(ask(llm(t, 100))).isEmpty();
        assertThat(t.prompts).hasSize(2);
    }

    @Test
    void transportFailureIsEmptyWithoutRetrying() {
        var calls = new int[1];
        LlmTransport down = (s, u) -> {
            calls[0]++;
            throw new LlmException("model endpoint answered 503");
        };
        assertThat(ask(llm(down, 100))).isEmpty();
        assertThat(calls[0]).isEqualTo(1);
    }

    @Test
    void spendGuardStopsCallingOnceTheDailyAllowanceIsUsed() {
        var t = new Scripted("{\"word\":\"leash\",\"n\":1}");
        var llm = llm(t, 2);
        assertThat(ask(llm)).isPresent();
        assertThat(ask(llm)).isPresent();
        assertThat(ask(llm)).isEmpty();
        assertThat(t.prompts).hasSize(2);
    }

    @Test
    void budgetResetsTheNextDay() {
        var now = new Instant[]{Instant.parse("2026-11-09T23:59:00Z")};
        Clock moving = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId z) { return this; }
            public Instant instant() { return now[0]; }
        };
        var budget = new LlmBudget(1, moving);
        assertThat(budget.tryAcquire()).isTrue();
        assertThat(budget.tryAcquire()).isFalse();
        now[0] = Instant.parse("2026-11-10T00:01:00Z");
        assertThat(budget.tryAcquire()).isTrue();
    }

    @Test
    void zeroBudgetMeansNoCalls() {
        var t = new Scripted("{\"word\":\"leash\",\"n\":1}");
        assertThat(ask(llm(t, 0))).isEmpty();
        assertThat(t.prompts).isEmpty();
    }

    @Test
    void readsOpenAiStyleResponses() {
        var res = java.util.Map.<String, Object>of("choices",
                List.of(java.util.Map.of("message", java.util.Map.of("role", "assistant", "content", "{}"))));
        assertThat(OpenAiCompatibleTransport.content(res)).isEqualTo("{}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> OpenAiCompatibleTransport.content(java.util.Map.of()))
                .isInstanceOf(LlmException.class);
    }
}
