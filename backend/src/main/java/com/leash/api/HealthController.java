package com.leash.api;

import com.leash.config.LeashProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the dashboard's status pill reads. Never exposes secrets, only whether they're set. */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final LeashProperties props;

    public HealthController(LeashProperties props) {
        this.props = props;
    }

    @GetMapping("/health")
    public Health health() {
        return new Health("ok", props.paypal().configured(), props.paypal().isSandbox(), props.llm().configured());
    }

    public record Health(String status, boolean paypalConfigured, boolean sandbox, boolean aiConfigured) {}
}
