package com.leash.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Lets the Vite dev server (localhost:5173) call the API. In production the frontend is served from the same origin. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final LeashProperties props;
    private final com.leash.auth.OwnerGate gate;

    public WebConfig(LeashProperties props, com.leash.auth.OwnerGate gate) {
        this.props = props;
        this.gate = gate;
    }

    @Override
    public void addInterceptors(org.springframework.web.servlet.config.annotation.InterceptorRegistry registry) {
        registry.addInterceptor(gate);
    }

    /** Spring only serves index.html at the root by itself; the Larder shop lives in a folder. */
    @Override
    public void addViewControllers(org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry) {
        registry.addViewController("/larder").setViewName("redirect:/larder/");
        registry.addViewController("/larder/").setViewName("forward:/larder/index.html");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String origins = props.corsOrigins() == null ? "" : props.corsOrigins();
        registry.addMapping("/api/**")
                .allowedOrigins(origins.split(","))
                .allowedMethods("GET", "POST", "PUT", "DELETE");
    }
}
