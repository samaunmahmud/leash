package com.leash.config;

import com.leash.policy.PolicyEngine;
import com.leash.risk.InjectionHeuristics;
import com.leash.risk.RiskChecker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

@Configuration
public class PolicyConfig {

    /** Quiet hours are judged in UK time for the demo. Per-user zones come with real accounts (cut list). */
    @Bean
    PolicyEngine policyEngine() {
        return new PolicyEngine(ZoneId.of("Europe/London"));
    }

    @Bean
    RiskChecker riskChecker() {
        return new InjectionHeuristics();
    }
}
