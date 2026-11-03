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

    /** Says loudly when nothing will survive a restart, so nobody records a demo thinking data is kept. */
    @Bean
    ApplicationRunner warnIfInMemory(@org.springframework.beans.factory.annotation.Value("${spring.datasource.url:}") String url) {
        return args -> {
            if (url.startsWith("jdbc:h2:mem:")) {
                org.slf4j.LoggerFactory.getLogger(AppConfig.class).warn(
                        "Using an in-memory database: data is lost on restart. Run `docker compose up -d` and set LEASH_DB_URL (see .env.example).");
            }
        };
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
