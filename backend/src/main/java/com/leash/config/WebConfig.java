package com.leash.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the Vite dev server (localhost:5173) call the API. In production the frontend is served from the same origin. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LeashProperties props;

    public WebConfig(LeashProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = props.corsOrigins() == null ? "" : props.corsOrigins();
        registry.addMapping("/api/**")
                .allowedOrigins(origins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
