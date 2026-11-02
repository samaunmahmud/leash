package com.leash.policy;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class PeriodsTest {

    static final ZoneId UK = ZoneId.of("Europe/London");

    @Test
    void weekStartsMondayMidnightUkTime() {
        // Sunday 15 Nov 2026, 23:30 UK → week began Monday 9 Nov 00:00 UK (GMT, so 00:00Z).
        assertThat(Periods.start(Period.WEEK, Instant.parse("2026-11-15T23:30:00Z"), UK))
                .isEqualTo(Instant.parse("2026-11-09T00:00:00Z"));
        // Monday itself, just after midnight, is the new week.
        assertThat(Periods.start(Period.WEEK, Instant.parse("2026-11-16T00:00:01Z"), UK))
                .isEqualTo(Instant.parse("2026-11-16T00:00:00Z"));
    }

    @Test
    void summerTimeShiftsTheBoundaryNotTheDay() {
        // Wed 1 Jul 2026 in BST (UTC+1): week began Mon 29 Jun 00:00 BST = Sun 28 Jun 23:00Z.
        assertThat(Periods.start(Period.WEEK, Instant.parse("2026-07-01T09:00:00Z"), UK))
                .isEqualTo(Instant.parse("2026-06-28T23:00:00Z"));
    }

    @Test
    void dayMonthAndTotal() {
        Instant now = Instant.parse("2026-11-12T15:00:00Z");
        assertThat(Periods.start(Period.DAY, now, UK)).isEqualTo(Instant.parse("2026-11-12T00:00:00Z"));
        assertThat(Periods.start(Period.MONTH, now, UK)).isEqualTo(Instant.parse("2026-11-01T00:00:00Z"));
        assertThat(Periods.start(Period.TOTAL, now, UK)).isEqualTo(Instant.EPOCH);
    }
}
