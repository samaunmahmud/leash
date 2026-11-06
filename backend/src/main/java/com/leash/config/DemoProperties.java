package com.leash.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * The hosted public demo (T17). With {@code publicMode} on, anyone can play with the three demo leashes (run the
 * scenario, answer tugs, snap back, Regret) while the owner passphrase guards everything that isn't demo data: agent
 * keys, the saved PayPal, creating or editing leashes.
 *
 * @param publicMode       {@code LEASH_DEMO_PUBLIC}: banner, hourly reset, rate limits, owner gate
 * @param ownerPassphrase  {@code LEASH_OWNER_PASSPHRASE}. Set it and owner actions need it, public mode or not. In
 *                         public mode with no passphrase, owner actions are refused outright (fail closed).
 * @param resetEvery       how often public mode puts the demo leashes back (default one hour)
 * @param seedHistory      add the clearly-labelled past tugs that make the mandate-learning suggestion appear; null
 *                         means "same as publicMode"
 * @param writesPerMinute  per visitor, for anything that isn't a GET
 * @param demoRunsPerHour  for everyone together: each run makes up to three AI calls and three PayPal orders
 */
@ConfigurationProperties(prefix = "leash.demo")
public record DemoProperties(boolean publicMode, String ownerPassphrase, Duration resetEvery, Boolean seedHistory,
                             int writesPerMinute, int demoRunsPerHour) {

    public DemoProperties {
        ownerPassphrase = ownerPassphrase == null || ownerPassphrase.isBlank() ? null : ownerPassphrase.strip();
        if (resetEvery == null || resetEvery.isNegative() || resetEvery.isZero()) resetEvery = Duration.ofHours(1);
        if (writesPerMinute <= 0) writesPerMinute = 30;
        if (demoRunsPerHour <= 0) demoRunsPerHour = 30;
    }

    /** Owner actions are checked at all. Off only for a local run with no passphrase (the old open dashboard). */
    public boolean gated() {
        return publicMode || ownerPassphrase != null;
    }

    public boolean seedsHistory() {
        return seedHistory != null ? seedHistory : publicMode;
    }
}
