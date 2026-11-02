package com.leash.policy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** When a leash's current period began. Weeks start on Monday (UK convention), judged in the leash's time zone. */
public final class Periods {
    private Periods() {}

    public static Instant start(Period period, Instant now, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        return switch (period) {
            case DAY -> today.atStartOfDay(zone).toInstant();
            case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant();
            case MONTH -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant();
            case TOTAL -> Instant.EPOCH;
        };
    }
}
