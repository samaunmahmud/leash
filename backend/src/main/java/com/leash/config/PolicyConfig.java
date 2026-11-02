package com.leash.config;

import com.leash.policy.PolicyEngine;
import com.leash.risk.CompositeRiskChecker;
import com.leash.risk.HeuristicRiskChecker;
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

    /** Heuristics always run (no key needed). T06 adds the LLM checker alongside them. */
    @Bean
    RiskChecker riskChecker() {
        return new CompositeRiskChecker(java.util.List.of(new HeuristicRiskChecker()));
    }
}
