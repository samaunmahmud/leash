package com.leash.api;

import com.leash.MutableClock;
import com.leash.ledger.LedgerRepository;
import com.leash.mandate.DemoLeashes;
import com.leash.mandate.MandateService;
import com.leash.paypal.FakePayPalServer;
import com.leash.vault.WalletRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** T11: save the owner's PayPal once, then approved purchases are paid with no popup. PayPal is a local fake. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class VaultFlowTest {

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

    @Value("${local.server.port}") int port;
    @Autowired LedgerRepository ledger;
    @Autowired WalletRepository wallets;
    @Autowired MandateService leashes;
    @Autowired MutableClock clock;
    @Autowired JdbcClient jdbc;

    Http http;

    @BeforeEach
    void reset() {
        ledger.deleteAll();
        wallets.delete();
        DemoLeashes.all().forEach(leashes::seed);
        fake.reset();
        clock.reset();
        http = new Http(port);
    }

    static String grocery(long amount, String requestId) {
        var m = new LinkedHashMap<String, Object>();
        m.put("agentId", "agent-larder");
        m.put("requestId", requestId);
        m.put("merchant", "Larder");
        m.put("category", "groceries");
        m.put("description", "Milk, eggs, bread");
        m.put("amountMinor", amount);
        m.put("currency", "GBP");
        m.put("task", "Buy this week's groceries");
        return Http.json(m);
    }

    void saveWallet() {
        var setup = http.post("/api/wallet/setup", "{}");
        assertThat(setup.status()).isEqualTo(200);
        assertThat(http.get("/api/paypal/vault/return?approval_token_id=x").body()).contains("PayPal saved");
    }

    @Test
    void setupSendsOnlySpecFieldsAndGivesAnApproveLink() {
        var res = http.post("/api/wallet/setup", "{}");
        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("status").asString()).isEqualTo("PENDING");
        assertThat(res.json().get("approveUrl").asString()).startsWith("https://www.sandbox.paypal.com/");

        var req = fake.last("POST", "/v3/vault/setup-tokens");
        assertThat(req.header("PayPal-Request-Id")).startsWith("setup-");
        @SuppressWarnings("unchecked")
        var paypal = (Map<String, Object>) ((Map<String, Object>) req.body().get("payment_source")).get("paypal");
        assertThat(paypal).containsOnlyKeys("usage_type", "customer_type", "permit_multiple_payment_tokens",
                "usage_pattern", "experience_context");
        assertThat(paypal).containsEntry("usage_type", "MERCHANT").containsEntry("customer_type", "CONSUMER");
        @SuppressWarnings("unchecked")
        var exp = (Map<String, Object>) paypal.get("experience_context");
        assertThat(exp).containsOnlyKeys("brand_name", "shipping_preference", "return_url", "cancel_url");
        assertThat((String) exp.get("return_url")).endsWith("/api/paypal/vault/return");
    }

    @Test
    void approvingSavesOnlyTheTokenIdAndAMaskedHint() {
        saveWallet();

        var view = http.get("/api/wallet").json();
        assertThat(view.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(view.get("hint").asString()).isEqualTo("s•••@personal.example.com");

        var req = fake.last("POST", "/v3/vault/payment-tokens");
        assertThat(req.body().toString()).contains("SETUP_TOKEN");

        Map<String, Object> row = jdbc.sql("SELECT * FROM paypal_wallet").query().singleRow();
        assertThat(row.toString()).doesNotContain("sam@personal", "PAYERID123", "CUST-1", "Owner");
        assertThat(row.get("payment_token_id").toString()).startsWith("VAULT-");
    }

    @Test
    void approvedPurchaseIsPaidWithTheSavedWalletAndNoPopup() {
        saveWallet();
        var res = http.post("/api/purchases", grocery(1_840, "vault-1"));
        assertThat(res.status()).isEqualTo(201);
        var body = res.json();
        assertThat(body.get("outcome").asString()).isEqualTo("APPROVE");
        assertThat(body.get("status").asString()).isEqualTo("PAID");
        assertThat(body.get("paypalOrderId").asString()).startsWith("ORDER-");
        assertThat(body.get("paypalCaptureId").asString()).startsWith("CAP-");
        assertThat(body.get("approveUrl").isNull()).isTrue();

        var order = fake.last("POST", "/v2/checkout/orders");
        assertThat(order.header("PayPal-Request-Id")).isEqualTo("vault-vault-1");
        assertThat(order.body().toString()).contains("vault_id=VAULT-", "payment_initiator=MERCHANT", "usage=SUBSEQUENT");
        assertThat(fake.count("POST", "/v2/checkout/orders/")).as("no separate capture call").isZero();
    }

    @Test
    void anApprovedTugIsPaidWithTheSavedWalletToo() {
        saveWallet();
        var tug = http.post("/api/purchases", grocery(3_200, "vault-tug")).json();
        assertThat(tug.get("outcome").asString()).isEqualTo("TUG");
        assertThat(fake.count("POST", "/v2/checkout/orders")).as("a tug pays nothing until approved").isZero();

        var approved = http.post("/api/tugs/" + tug.get("tugId").asString() + "/approve", "{}");
        assertThat(approved.status()).isEqualTo(200);
        assertThat(ledger.find("vault-tug").orElseThrow().status().name()).isEqualTo("PAID");
    }

    @Test
    void blockedPurchasesNeverTouchTheWallet() {
        saveWallet();
        var m = grocery(50_000, "vault-block").replace("\"groceries\"", "\"gift cards\"");
        assertThat(http.post("/api/purchases", m).json().get("outcome").asString()).isEqualTo("BLOCK");
        assertThat(fake.count("POST", "/v2/checkout/orders")).isZero();
    }

    @Test
    void aDeclinedWalletFallsBackToAnApproveLinkInsteadOfLosingThePurchase() {
        saveWallet();
        fake.failNextVaultOrderWith = 422;
        var body = http.post("/api/purchases", grocery(1_840, "vault-decl")).json();
        assertThat(body.get("status").asString()).isEqualTo("AWAITING_PAYER");
        assertThat(body.get("approveUrl").asString()).contains("checkoutnow");
        assertThat(ledger.find("vault-decl").orElseThrow().statusDetail())
                .contains("saved PayPal couldn't be charged", "INSTRUMENT_DECLINED");
    }

    @Test
    void finishingBeforeApprovingKeepsItPendingAndSaysWhy() {
        http.post("/api/wallet/setup", "{}");
        fake.setupApproval = false;
        var res = http.post("/api/wallet/complete", "{}");
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.body()).contains("Approve it in the sandbox window");
        assertThat(http.get("/api/wallet").json().get("status").asString()).isEqualTo("PENDING");
    }

    @Test
    void forgettingDeletesItAtPayPalAndPurchasesAskAgain() {
        saveWallet();
        assertThat(http.delete("/api/wallet").json().get("status").asString()).isEqualTo("NONE");
        assertThat(fake.count("DELETE", "/v3/vault/payment-tokens/VAULT-")).isEqualTo(1);

        var body = http.post("/api/purchases", grocery(1_840, "after-forget")).json();
        assertThat(body.get("status").asString()).isEqualTo("AWAITING_PAYER");
    }

    @Test
    void cancellingInPayPalLeavesNoWallet() {
        http.post("/api/wallet/setup", "{}");
        assertThat(http.get("/api/paypal/vault/cancel").body()).contains("Nothing was saved");
        assertThat(http.get("/api/wallet").json().get("status").asString()).isEqualTo("NONE");
    }

    @Test
    void cantStartASecondSetupOverAnActiveWallet() {
        saveWallet();
        assertThat(http.post("/api/wallet/setup", "{}").status()).isEqualTo(409);
    }

    /** Regression: the first real sandbox attempt answered 422 before approval; a fixed request ID could replay it. */
    @Test
    void eachFinishAttemptUsesAFreshRequestIdAndTheLinkSurvivesInTheDatabase() {
        http.post("/api/wallet/setup", "{}");
        fake.setupApproval = false;
        http.post("/api/wallet/complete", "{}");
        String first = fake.last("POST", "/v3/vault/payment-tokens").header("PayPal-Request-Id");
        assertThat(http.get("/api/wallet").json().get("approveUrl").asString()).startsWith("https://www.sandbox.paypal.com/");

        fake.setupApproval = null;
        assertThat(http.post("/api/wallet/complete", "{}").json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(fake.last("POST", "/v3/vault/payment-tokens").header("PayPal-Request-Id")).isNotEqualTo(first);
        assertThat(http.get("/api/wallet").json().get("approveUrl").isNull()).isTrue();
    }
}
