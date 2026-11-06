package com.leash.auth;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed-window counters, in memory: enough to stop one visitor hammering the public demo, not a security boundary.
 * Windows that have ended are dropped when the map grows, so it can't fill up with old visitors.
 */
@Component
public class RateLimiter {

    private record Window(long start, int count) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts one hit against {@code key}; false when it's over {@code limit} for the current window. */
    public boolean allow(String key, int limit, Duration window) {
        long now = clock.millis();
        long size = window.toMillis();
        if (windows.size() > 10_000) windows.values().removeIf(w -> now - w.start() >= size);
        Window w = windows.compute(key, (k, old) ->
                old == null || now - old.start() >= size ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
        return w.count() <= limit;
    }

    /** Seconds until {@code key}'s window ends (for Retry-After). */
    public long retryAfterSeconds(String key, Duration window) {
        Window w = windows.get(key);
        if (w == null) return 1;
        return Math.max(1, (w.start() + window.toMillis() - clock.millis() + 999) / 1000);
    }

    public void clear() {
        windows.clear();
    }
}
