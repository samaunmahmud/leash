package com.leash.api;

import com.leash.paypal.PayPalClient.WebhookHeaders;
import com.leash.webhook.WebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where PayPal delivers webhook events (register {@code <app url>/api/paypal/webhook} in the developer dashboard and put
 * its ID in LEASH_PAYPAL_WEBHOOK_ID).
 *
 * <p>The body is taken as the raw string so it can go back to PayPal's verify API byte for byte. Replies: 200 once
 * PayPal vouched for the event (even if Leash had nothing to do with it, so PayPal stops retrying), 400 when it didn't,
 * 503 when webhooks aren't configured. If PayPal's verify API itself is down the reply is 502 and PayPal retries later.
 * Headers: https://developer.paypal.com/api/rest/webhooks/rest/#link-verifywebhooksignature
 */
@RestController
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/api/paypal/webhook")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestBody(required = false) String body,
            @RequestHeader(name = "PAYPAL-AUTH-ALGO", required = false) String authAlgo,
            @RequestHeader(name = "PAYPAL-CERT-URL", required = false) String certUrl,
            @RequestHeader(name = "PAYPAL-TRANSMISSION-ID", required = false) String transmissionId,
            @RequestHeader(name = "PAYPAL-TRANSMISSION-SIG", required = false) String transmissionSig,
            @RequestHeader(name = "PAYPAL-TRANSMISSION-TIME", required = false) String transmissionTime) {
        var headers = new WebhookHeaders(authAlgo, certUrl, transmissionId, transmissionSig, transmissionTime);
        WebhookService.Result r = webhooks.handle(headers, body == null ? "" : body);
        var out = new LinkedHashMap<String, Object>();
        out.put("verified", r.verified());
        out.put("result", r.result());
        if (r.entryId() != null) out.put("entryId", r.entryId());
        return ResponseEntity.status(r.verified() ? HttpStatus.OK : HttpStatus.BAD_REQUEST).body(out);
    }
}
