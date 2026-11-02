package com.leash;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Picked up by component scanning in every Spring test: replaces the system clock, so quiet hours and period
 * boundaries don't make tests pass or fail depending on when they run.
 */
@Configuration
public class TestClockConfig {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }
}
