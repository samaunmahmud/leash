package com.leash.store;

import com.leash.LeashApplication;
import com.leash.agent.AgentRepository;
import com.leash.demo.DemoAgent;
import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateInput;
import com.leash.mandate.MandateService;
import com.leash.policy.Outcome;
import com.leash.policy.Period;
import com.leash.purchase.PurchaseCommand;
import com.leash.purchase.PurchaseService;
import com.leash.purchase.TugService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T08's bar: data survives an app restart. Boots the whole app, writes through the real services (leash, purchases,
 * tug, snap back, the demo run), shuts it down, boots a fresh app on the same database and reads it all back.
 * Subclasses pick the database.
 */
abstract class RestartPersistenceTest {

    abstract String url();

    abstract String user();

    abstract String password();

    ConfigurableApplicationContext boot() {
        // Command-line args beat the test config file, which would otherwise point at a fresh in-memory H2.
        return new SpringApplicationBuilder(LeashApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + url(),
                "--spring.datasource.username=" + user(),
                "--spring.datasource.password=" + password());
    }

    static MandateInput giftMandate() {
        return new MandateInput("Birthday gifts", "GBP", Period.MONTH, 12_000, 3_000, 6_000, Set.of("Waterstones"),
                Set.of(), Set.of("books"), Set.of("gift cards"), 10, "23:00", "07:00", true,
                "Gifts, £120 a month at Waterstones, ask me over £30");
    }

    static PurchaseCommand book(String agentId, String requestId, long amount) {
        return new PurchaseCommand(agentId, null, requestId, "Waterstones", "books",
                "A novel for Sam", amount, "GBP", "Buy birthday gifts from the list", "on the list", null, null);
    }

    @Test
    void everythingSurvivesARestart() {
        String giftAgent;
        long entriesBefore;
        try (var app = boot()) {
            var leashes = app.getBean(MandateService.class);
            var purchases = app.getBean(PurchaseService.class);
            var tugs = app.getBean(TugService.class);
            // The demo first: it resets the ledger and the demo leashes, then plays approve / tug / block.
            var run = app.getBean(DemoAgent.class).run(0, true);
            assertThat(run.results()).extracting(r -> r.outcome())
                    .containsExactly(Outcome.APPROVE, Outcome.TUG, Outcome.BLOCK);

            giftAgent = leashes.create("Gift bot", "other", giftMandate()).agent().id();
            assertThat(purchases.request(book(giftAgent, "restart-1", 2_000)).outcome()).isEqualTo(Outcome.APPROVE);
            assertThat(purchases.request(book(giftAgent, "restart-2", 5_000)).outcome()).isEqualTo(Outcome.TUG);
            assertThat(purchases.request(book(giftAgent, "restart-3", 4_500)).outcome()).isEqualTo(Outcome.TUG);
            tugs.deny("tug-restart-3");
            leashes.snap(DemoLeashes.OFFICE_AGENT);
            entriesBefore = app.getBean(LedgerRepository.class).count();
        }

        try (var app = boot()) {
            var leashes = app.getBean(MandateService.class);
            var repo = app.getBean(LedgerRepository.class);

            // Seeding runs only on an empty database: the restart didn't add or reset anything.
            assertThat(app.getBean(AgentRepository.class).count()).isEqualTo(4);
            assertThat(leashes.get(DemoLeashes.OFFICE_AGENT).agent().revoked()).isTrue();

            var gift = leashes.get(giftAgent);
            assertThat(gift.agent().name()).isEqualTo("Gift bot");
            assertThat(gift.mandate().allowedMerchants()).containsExactly("waterstones");
            assertThat(gift.mandate().quietStart()).hasToString("23:00");
            assertThat(gift.sourceText()).startsWith("Gifts, £120");

            assertThat(repo.count()).isEqualTo(entriesBefore);
            var first = repo.find("restart-1").orElseThrow();
            assertThat(first.status()).isEqualTo(EntryStatus.PAYPAL_OFF);
            assertThat(first.request().amountMinor()).isEqualTo(2_000);
            assertThat(first.request().requestedAt()).isNotNull();
            assertThat(repo.find("restart-2").orElseThrow().status()).isEqualTo(EntryStatus.TUG_PENDING);
            assertThat(repo.find("restart-3").orElseThrow().status()).isEqualTo(EntryStatus.DENIED);

            var blocked = repo.list(DemoLeashes.GROCERY_AGENT, 50).stream()
                    .filter(e -> e.decision().outcome() == Outcome.BLOCK).findFirst().orElseThrow();
            assertThat(blocked.decision().reasons()).extracting(r -> r.code().name()).contains("PROMPT_INJECTION");

            // The idempotency key still works across the restart: same requestId, same entry, no second purchase.
            var replay = app.getBean(PurchaseService.class).request(book(giftAgent, "restart-1", 2_000));
            assertThat(replay.replayed()).isTrue();
            assertThat(repo.count()).isEqualTo(entriesBefore);

            // Pending tugs still count toward the length after a restart.
            var usage = app.getBean(com.leash.ledger.LedgerService.class).usage(leashes.get(DemoLeashes.GROCERY_AGENT).mandate());
            assertThat(usage.pendingMinor()).isGreaterThanOrEqualTo(3_200);
        }
    }
}
