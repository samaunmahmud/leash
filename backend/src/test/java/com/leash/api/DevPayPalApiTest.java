package com.leash.api;

import com.leash.paypal.FakePayPalServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/** The manual-testing endpoint, end to end over HTTP, with PayPal replaced by a local fake. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DevPayPalApiTest {

    static final FakePayPalServer fake = new FakePayPalServer();

    @DynamicPropertySource
    static void paypal(DynamicPropertyRegistry r) {
        r.add("leash.paypal.base-url", fake::baseUrl);
        r.add("leash.paypal.client-id", () -> "id");
        r.add("leash.paypal.client-secret", () -> "secret");
    }

    @AfterAll
    static void stop() {
        fake.close();
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> post(String path, String json) throws Exception {
        var req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void createsAndCapturesAnOrder() throws Exception {
        var created = post("/api/dev/paypal/order", "{\"amountMinor\":1840,\"currency\":\"GBP\",\"description\":\"test\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"status\":\"PAYER_ACTION_REQUIRED\"", "\"approveUrl\":\"https://www.sandbox.paypal.com/");
        String id = created.body().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

        var captured = post("/api/dev/paypal/order/" + id + "/capture", "");
        assertThat(captured.statusCode()).isEqualTo(200);
        assertThat(captured.body()).contains("\"status\":\"COMPLETED\"", "\"captureId\":\"CAP-");
        // Payer details in PayPal's response never reach our API.
        assertThat(captured.body()).doesNotContain("buyer@example.com", "PAYERID");
    }

    @Test
    void paypalErrorsBecomeProblemJson() throws Exception {
        fake.failNextCreateWith = 422;
        var res = post("/api/dev/paypal/order", "{}");
        assertThat(res.statusCode()).isEqualTo(502);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).contains("problem+json"));
        assertThat(res.body()).contains("PAYEE_ACCOUNT_RESTRICTED").doesNotContain("secret payer stuff");
    }

    @Test
    void rejectsNonPositiveAmounts() throws Exception {
        assertThat(post("/api/dev/paypal/order", "{\"amountMinor\":0}").statusCode()).isEqualTo(400);
    }
}
