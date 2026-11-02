package com.leash.config;

import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class AppConfig {

    /** One clock for the whole app, so tests can control time. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Seeds the three demo leashes on an empty database. */
    @Bean
    @ConditionalOnBooleanProperty(name = "leash.seed-demo", matchIfMissing = true)
    ApplicationRunner seedDemoLeashes(MandateService leashes, com.leash.agent.AgentRepository agents) {
        return args -> {
            if (agents.count() == 0) DemoLeashes.all().forEach(leashes::seed);
        };
    }
}
