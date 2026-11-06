package com.leash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LeashApplication {
    public static void main(String[] args) {
        com.leash.config.DatabaseUrl.applyFrom(System.getenv()); // Render's postgres:// URL → JDBC
        SpringApplication.run(LeashApplication.class, args);
    }
}
