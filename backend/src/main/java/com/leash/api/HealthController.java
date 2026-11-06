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
    private final com.leash.config.DemoProperties demo;
    private final com.leash.auth.OwnerGate gate;

    public HealthController(LeashProperties props, com.leash.config.DemoProperties demo, com.leash.auth.OwnerGate gate) {
        this.props = props;
        this.demo = demo;
        this.gate = gate;
    }

    @GetMapping("/health")
    public Health health() {
        return new Health("ok", props.paypal().configured(), props.paypal().isSandbox(), props.llm().configured(),
                demo.publicMode(), demo.gated(), gate.passphraseSet(), demo.publicMode() ? demo.resetEvery().toMinutes() : 0);
    }

    /**
     * @param publicDemo   show the "public sandbox demo" banner
     * @param ownerGated   owner actions need the passphrase (the dashboard shows "Unlock owner mode")
     * @param ownerEnabled a passphrase is set; false in public mode means owner actions are off entirely
     * @param resetMinutes how often the demo resets, 0 when it doesn't
     */
    public record Health(String status, boolean paypalConfigured, boolean sandbox, boolean aiConfigured,
                         boolean publicDemo, boolean ownerGated, boolean ownerEnabled, long resetMinutes) {}
}
