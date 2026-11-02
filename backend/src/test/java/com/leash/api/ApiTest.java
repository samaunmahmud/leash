package com.leash.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** Boots the whole app on a random port and calls it over real HTTP, so wiring and JSON are both covered. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"leash.paypal.client-id=", "leash.paypal.client-secret="})
class ApiTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthReportsSandboxAndUnconfiguredPayPal() throws Exception {
        var res = get("/api/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"ok\"", "\"sandbox\":true", "\"paypalConfigured\":false");
    }

    @Test
    void devPayPalEndpointSaysWhenPayPalIsNotConfigured() throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/dev/paypal/order"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(503);
        assertThat(res.body()).contains("isn't configured");
    }

    @Test
    void approvedPurchaseWithoutPayPalIsRecordedAsPayPalOff() throws Exception {
        var body = "{\"agentId\":\"agent-office\",\"merchant\":\"Viking\",\"category\":\"stationery\","
                + "\"description\":\"Pens\",\"amountMinor\":1200,\"currency\":\"GBP\"}";
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/purchases"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var res = http.send(req, HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"outcome\":\"APPROVE\"", "\"status\":\"PAYPAL_OFF\"");
    }

    @Test
    void demoLedgerShowsApproveTugBlock() throws Exception {
        var res = get("/api/demo/ledger");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"APPROVE\"", "\"TUG\"", "\"BLOCK\"", "PROMPT_INJECTION");
    }
}
