package com.leash;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can set and move. Starts at Monday 9 Nov 2026, 12:00 UTC (noon in London: outside quiet hours). */
public final class MutableClock extends Clock {

    public static final Instant START = Instant.parse("2026-11-09T12:00:00Z");
    private volatile Instant now = START;

    public void set(Instant i) {
        now = i;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    public void reset() {
        now = START;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
