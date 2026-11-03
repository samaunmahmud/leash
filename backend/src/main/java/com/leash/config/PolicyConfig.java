package com.leash.config;

import com.leash.policy.PolicyEngine;
import com.leash.risk.CompositeRiskChecker;
import com.leash.llm.StructuredLlm;
import com.leash.risk.HeuristicRiskChecker;
import com.leash.risk.LlmRiskChecker;
import com.leash.risk.RiskChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

@Configuration
public class PolicyConfig {

    /** Quiet hours and period boundaries are judged in UK time for the demo. Per-user zones come with real accounts (cut list). */
    public static final ZoneId ZONE = ZoneId.of("Europe/London");

    @Bean
    PolicyEngine policyEngine() {
        return new PolicyEngine(ZONE);
    }

    /**
     * Heuristics always run (no key needed). With a model configured, the LLM checker runs alongside them; its reasons
     * are pooled with the heuristics' and only ever escalate the engine's decision.
     */
    @Bean
    RiskChecker riskChecker(StructuredLlm llm) {
        var checkers = new java.util.ArrayList<RiskChecker>();
        checkers.add(new HeuristicRiskChecker());
        if (llm.enabled()) checkers.add(new LlmRiskChecker(llm));
        return new CompositeRiskChecker(checkers);
    }
}
