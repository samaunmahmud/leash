package com.leash.config;

import com.leash.llm.LlmBudget;
import com.leash.llm.OpenAiCompatibleTransport;
import com.leash.llm.StructuredLlm;
import com.leash.mandate.MandateParser;
import jakarta.validation.Validator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The AI side of Leash. With no LEASH_LLM_* settings the model simply isn't called: the mandate parser and risk checks
 * fall back to their rule-based versions and the app works the same, minus the AI's second opinion.
 */
@Configuration
public class LlmConfig {

    @Bean
    StructuredLlm structuredLlm(LeashProperties props, Clock clock, Validator validator) {
        var cfg = props.llm();
        var transport = cfg.configured() ? new OpenAiCompatibleTransport(cfg) : null;
        return new StructuredLlm(transport, new LlmBudget(cfg.maxCallsPerDay(), clock), validator);
    }

    @Bean
    MandateParser mandateParser(StructuredLlm llm, Validator validator) {
        return new MandateParser(llm, validator);
    }
}
