package com.leash.api;

import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** The dashboard's live feed: a purchase and a snap back both arrive over SSE without polling. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StreamTest {

    @Value("${local.server.port}")
    int port;

    @Autowired StreamController stream;
    @Autowired LedgerRepository repo;
    @Autowired MandateService leashes;

    @Test
    void pushesLedgerAndLeashEvents() throws Exception {
        repo.deleteAll();
        DemoLeashes.all().forEach(leashes::seed);
        List<String> lines = new CopyOnWriteArrayList<>();
        var client = HttpClient.newHttpClient();
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/stream"))
                .header("Accept", "text/event-stream").build();
        var pending = client.sendAsync(req, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(res -> res.body().forEach(lines::add));

        waitFor(() -> stream.clientCount() == 1);
        var http = new Http(port);
        http.post("/api/purchases", Http.json(PurchaseFlowTest.grocery(1_840)));
        http.post("/api/leashes/agent-office/snap", "");

        waitFor(() -> lines.contains("event:leash"));
        assertThat(lines).contains("event:hello", "event:ledger", "event:leash");
        assertThat(String.join("\n", lines)).contains("\"type\":\"entry.created\"", "\"revoked\":true");
        pending.cancel(true);
    }

    @Test
    void leashCardsIncludeUsage() {
        repo.deleteAll();
        DemoLeashes.all().forEach(leashes::seed);
        var http = new Http(port);
        http.post("/api/purchases", Http.json(PurchaseFlowTest.grocery(1_840)));
        var body = PurchaseFlowTest.grocery(3_200);
        body.put("description", "Big shop");
        http.post("/api/purchases", Http.json(body));
        var card = http.get("/api/leashes/agent-larder").json();
        assertThat(card.get("usage").get("spentMinor").asLong()).isEqualTo(1_840);
        assertThat(card.get("usage").get("pendingMinor").asLong()).isEqualTo(3_200);
        assertThat(card.get("usage").get("periodStart").asString()).isEqualTo("2026-11-09T00:00:00Z");
    }

    static void waitFor(java.util.function.BooleanSupplier cond) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!cond.getAsBoolean() && System.nanoTime() < end) Thread.sleep(20);
        assertThat(cond.getAsBoolean()).as("condition within 5s").isTrue();
    }

    static final Duration UNUSED = Duration.ZERO;
}
