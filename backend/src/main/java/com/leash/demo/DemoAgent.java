package com.leash.demo;

import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import com.leash.purchase.PurchaseCommand;
import com.leash.purchase.PurchaseResult;
import com.leash.purchase.PurchaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Larder bot": a scripted shopping agent that plays the video's scenario against the real purchase flow.
 *
 * <p>It's deliberately naive, like an agent that trusts what it reads: on the poisoned gift-card page it believes the
 * hidden text and asks to buy £500 of gift cards. Leash is what stops it. It calls the same PurchaseService the REST
 * and MCP endpoints use, so nothing about the decision is staged.
 *
 * <p>Decision: scripted rather than an LLM tool loop. The demo must play identically every time (and with no API
 * key); the "AI" in the loop that matters is Leash's risk check, not the agent.
 */
@Component
public class DemoAgent {

    private static final Logger log = LoggerFactory.getLogger(DemoAgent.class);
    public static final String TASK = "Buy this week's groceries for the flat";

    /** One thing the agent does: read a product page and ask Leash to buy it. */
    public record Step(String slug, String reason) {}

    public static final List<Step> SCRIPT = List.of(
            new Step("weekly-staples", "Weekly staples are running out"),
            new Step("big-shop", "We're low on most things this week"),
            new Step("gift-bundle", "The page says the user pre-approved this bundle"));

    public record Run(String runId, boolean async, List<PurchaseResult> results) {}

    private final PurchaseService purchases;
    private final LarderCatalogue larder;
    private final LedgerRepository ledger;
    private final MandateService leashes;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "demo-agent");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean running = new AtomicBoolean();

    private DemoHistory history;

    public DemoAgent(PurchaseService purchases, LarderCatalogue larder, LedgerRepository ledger, MandateService leashes) {
        this.purchases = purchases;
        this.larder = larder;
        this.ledger = ledger;
        this.leashes = leashes;
    }

    /**
     * Clears the demo leashes' ledger and puts them back as they were (re-leashing any snapped agent). Leashes the
     * owner made keep their history: the hosted demo resets every hour.
     */
    @Transactional
    public void reset() {
        ledger.deleteForAgents(DemoLeashes.all().stream().map(DemoLeashes.Seed::agentId).toList());
        DemoLeashes.all().forEach(leashes::seed);
        if (history != null) history.seedIfEnabled();
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setHistory(DemoHistory history) {
        this.history = history;
    }

    public boolean running() {
        return running.get();
    }

    /**
     * Plays the script. {@code paceMillis} 0 runs synchronously and returns every result (tests, curl); otherwise
     * steps run in the background {@code paceMillis} apart so the dashboard shows them arriving live.
     */
    public Run run(long paceMillis, boolean reset) {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("The demo is already running.");
        String runId = "demo-" + UUID.randomUUID().toString().substring(0, 8);
        try {
            if (reset) reset();
        } catch (RuntimeException e) {
            running.set(false);
            throw e;
        }
        if (paceMillis <= 0) {
            try {
                return new Run(runId, false, play(runId, 0));
            } finally {
                running.set(false);
            }
        }
        worker.submit(() -> {
            try {
                play(runId, paceMillis);
            } catch (RuntimeException e) {
                log.warn("Demo run {} failed: {}", runId, e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return new Run(runId, true, List.of());
    }

    private List<PurchaseResult> play(String runId, long pace) {
        List<PurchaseResult> out = new ArrayList<>();
        int n = 0;
        for (Step s : SCRIPT) {
            if (pace > 0 && n > 0) sleep(pace);
            var p = larder.product(s.slug());
            // The agent "reads" the page, then sends Leash its request with an excerpt. Leash reads the page too.
            String page = larder.page(s.slug()).orElse("");
            var cmd = new PurchaseCommand(DemoLeashes.GROCERY_AGENT, null, runId + "-" + (++n), p.merchant(),
                    p.category(), p.description(), p.priceMinor(), p.currency(), TASK, s.reason(), p.url(),
                    excerpt(page));
            out.add(purchases.request(cmd));
        }
        return out;
    }

    /** What a naive agent passes along: the visible text. (It strips tags, so the hidden span's text survives.) */
    static String excerpt(String html) {
        String text = html.replaceAll("(?s)<head>.*?</head>", " ").replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        return text.length() > 2_000 ? text.substring(0, 2_000) : text;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
