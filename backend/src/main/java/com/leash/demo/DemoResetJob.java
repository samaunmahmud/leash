package com.leash.demo;

import com.leash.config.DemoProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Public mode: the demo leashes start clean and go back to clean every hour, so whatever one visitor did (snapped
 * every agent back, spent the lot) doesn't greet the next. A run in progress is left to finish; the next tick resets.
 */
@Component
public class DemoResetJob {

    private static final Logger log = LoggerFactory.getLogger(DemoResetJob.class);

    private final DemoProperties demo;
    private final DemoAgent agent;
    private final com.leash.auth.RateLimiter limits;

    public DemoResetJob(DemoProperties demo, DemoAgent agent, com.leash.auth.RateLimiter limits) {
        this.demo = demo;
        this.agent = agent;
        this.limits = limits;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        if (!demo.publicMode()) return;
        if (demo.ownerPassphrase() == null) {
            log.warn("Public demo with no LEASH_OWNER_PASSPHRASE: agent keys, saved PayPal and leash edits are switched off.");
        }
        reset("startup");
    }

    @Scheduled(fixedDelayString = "${leash.demo.reset-every:PT1H}", initialDelayString = "${leash.demo.reset-every:PT1H}")
    public void tick() {
        if (demo.publicMode()) reset("hourly");
    }

    void reset(String why) {
        if (agent.running()) {
            log.info("Demo reset ({}) skipped: a demo run is in progress.", why);
            return;
        }
        agent.reset();
        log.info("Demo leashes reset ({}).", why);
    }
}
