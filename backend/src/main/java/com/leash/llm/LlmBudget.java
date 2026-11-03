package com.leash.llm;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Spend guard: at most {@code maxCallsPerDay} model calls per UTC day. Past that, callers fall back to heuristics, so
 * a runaway agent (or a demo left looping) can't run up an API bill. 0 or less means no calls at all.
 */
public final class LlmBudget {

    private final int maxCallsPerDay;
    private final Clock clock;
    private LocalDate day;
    private int used;

    public LlmBudget(int maxCallsPerDay, Clock clock) {
        this.maxCallsPerDay = maxCallsPerDay;
        this.clock = clock;
    }

    /** Takes one call from today's allowance; false when it's spent. */
    public synchronized boolean tryAcquire() {
        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!today.equals(day)) {
            day = today;
            used = 0;
        }
        if (used >= maxCallsPerDay) return false;
        used++;
        return true;
    }

    public synchronized int usedToday() {
        return today().equals(day) ? used : 0;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
