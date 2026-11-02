package com.leash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class LeashApplication {
    public static void main(String[] args) {
        SpringApplication.run(LeashApplication.class, args);
    }
}
