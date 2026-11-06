package com.leash.demo;

import com.leash.config.DemoProperties;
import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.policy.Decision;
import com.leash.policy.Mandate;
import com.leash.policy.PolicyEngine;
import com.leash.policy.PurchaseRequest;
import com.leash.policy.SpendSnapshot;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Past-week tugs for the Larder bot, so the public demo can show mandate learning (T16 needs three approved
 * over-slack tugs; one demo run only makes one). Every row says it's a seeded example, the decision is the real
 * engine's for that request, and no PayPal order was made, so there are no PayPal IDs on these rows.
 */
@Component
public class DemoHistory {

    public static final String ID_PREFIX = "seed-";
    public static final String DETAIL = "Seeded example for the public demo: answered by the owner, no PayPal order was made.";

    record Past(int daysAgo, long amountMinor, String description) {}

    /** £26.50, £27.40, £29: over the £25 slack, under the cap, all approved: the advisor suggests £30. */
    static final List<Past> PAST = List.of(
            new Past(22, 2_650, "Weekly shop + cleaning spray (seeded example)"),
            new Past(15, 2_740, "Weekly shop + washing-up liquid (seeded example)"),
            new Past(8, 2_900, "Weekly shop + bin bags (seeded example)"));

    private final DemoProperties demo;
    private final LedgerRepository ledger;
    private final PolicyEngine engine;
    private final Clock clock;

    public DemoHistory(DemoProperties demo, LedgerRepository ledger, PolicyEngine engine, Clock clock) {
        this.demo = demo;
        this.ledger = ledger;
        this.engine = engine;
        this.clock = clock;
    }

    public void seedIfEnabled() {
        if (demo.seedsHistory()) seed();
    }

    /** Called right after a reset, so the rows don't exist yet. */
    void seed() {
        Mandate m = DemoLeashes.grocery();
        ZoneId london = ZoneId.of("Europe/London");
        ZonedDateTime today = clock.instant().atZone(london);
        int n = 0;
        for (Past p : PAST) {
            String id = ID_PREFIX + (++n);
            // Noon on that day: inside every rule but the slack, away from the midnight-6am quiet hours.
            Instant at = today.minusDays(p.daysAgo()).with(LocalTime.NOON).toInstant();
            var req = new PurchaseRequest(id, m.agentId(), m.id(), "Larder", "groceries", p.description(),
                    p.amountMinor(), m.currency(), "Buy this week's groceries for the flat",
                    "Running low on cleaning things too", null, at);
            Decision d = engine.evaluate(m, req, new SpendSnapshot(0, 0, false));
            ledger.insert(new LedgerEntry(id, req, d, null, EntryStatus.PAYPAL_OFF, DETAIL, null, null, null, null,
                    null, at, at.plus(Duration.ofMinutes(9))));
            ledger.insertTug(new LedgerRepository.Tug("tug-" + id, id, "APPROVED", at, at.plus(Duration.ofMinutes(9))));
        }
    }
}
