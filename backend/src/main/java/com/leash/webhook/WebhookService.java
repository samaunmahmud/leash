package com.leash.webhook;

import com.leash.api.NotConfiguredException;
import com.leash.config.LeashProperties;
import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerRepository;
import com.leash.ledger.LedgerService;
import com.leash.paypal.PayPalClient;
import com.leash.paypal.PayPalClient.WebhookHeaders;
import com.leash.policy.Money;
import com.leash.purchase.PaymentService;
import com.leash.store.Db;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps the ledger true to what PayPal says happened, even when the payer closes the tab or someone refunds from the
 * PayPal dashboard.
 *
 * <p>Order of work: (1) verify the signature with PayPal (an unverified event never touches the ledger), (2) skip an
 * event ID seen before (PayPal retries), (3) apply it. Each handler only moves an entry forward from the state it
 * expects, with a conditional update, so a webhook racing the return-URL capture or the Regret button can't undo or
 * double-apply anything. Webhooks can never create a payment: they only confirm or reverse ones Leash already made.
 *
 * <p>Event types and resources: https://developer.paypal.com/api/rest/webhooks/event-names/ . Envelope fields (id,
 * event_type, resource_type, resource) from notifications_webhooks_v1.json. Capture and refund resources from
 * payments_payment_v2.json: a capture's links[] has rel "up" → /v2/checkout/orders/{id}; a refund's rel "up" →
 * /v2/payments/captures/{id}. supplementary_data.related_ids is read only as a fallback (it's in the spec's
 * related_ids schema, not on the capture schema itself).
 */
@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern ORDER_LINK = Pattern.compile("/v2/checkout/orders/([A-Za-z0-9-]+)$");
    private static final Pattern CAPTURE_LINK = Pattern.compile("/v2/payments/captures/([A-Za-z0-9-]+)$");

    /** What happened to one delivery. {@code verified} false means PayPal didn't vouch for it. */
    public record Result(boolean verified, String result, String entryId) {}

    private final PayPalClient paypal;
    private final LeashProperties props;
    private final LedgerService ledger;
    private final LedgerRepository repo;
    private final PaymentService payments;
    private final JdbcClient jdbc;

    public WebhookService(PayPalClient paypal, LeashProperties props, LedgerService ledger, LedgerRepository repo,
                          PaymentService payments, JdbcClient jdbc) {
        this.paypal = paypal;
        this.props = props;
        this.ledger = ledger;
        this.repo = repo;
        this.payments = payments;
        this.jdbc = jdbc;
    }

    @SuppressWarnings("unchecked")
    public Result handle(WebhookHeaders headers, String rawEvent) {
        if (!paypal.configured() || !props.paypal().webhookConfigured()) {
            throw new NotConfiguredException("Webhooks are off: set LEASH_PAYPAL_WEBHOOK_ID (and PayPal credentials).");
        }
        if (!headers.complete()) return new Result(false, "missing PayPal signature headers", null);
        Map<String, Object> event;
        try {
            event = JSON.readValue(rawEvent, Map.class);
        } catch (RuntimeException e) {
            return new Result(false, "not JSON", null);
        }
        if (!paypal.verifyWebhook(headers, rawEvent)) {
            log.warn("Webhook {} failed PayPal signature verification; ignored", str(event.get("id")));
            return new Result(false, "signature not verified", null);
        }

        String eventId = str(event.get("id"));
        String type = str(event.get("event_type"));
        Map<String, Object> resource = event.get("resource") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (eventId == null || type == null) return new Result(true, "ignored: no event id or type", null);
        if (seen(eventId)) return new Result(true, "duplicate: already handled", null);

        Result r = switch (type) {
            case "CHECKOUT.ORDER.APPROVED" -> orderApproved(resource);
            case "PAYMENT.CAPTURE.COMPLETED" -> captureCompleted(resource);
            case "PAYMENT.CAPTURE.DENIED" -> captureDenied(resource);
            case "PAYMENT.CAPTURE.REFUNDED" -> captureRefunded(resource);
            default -> new Result(true, "ignored: " + type + " isn't used by Leash", null);
        };
        record(eventId, type, str(resource.get("id")), r);
        log.info("Webhook {} {} → {}{}", eventId, type, r.result(), r.entryId() == null ? "" : " (entry " + r.entryId() + ")");
        return r;
    }

    // ── Handlers ────────────────────────────────────────────────────────────────────────────────────────────

    /** The payer approved but never came back to the return URL (closed the tab): capture now. resource = order. */
    private Result orderApproved(Map<String, Object> order) {
        String orderId = str(order.get("id"));
        Optional<LedgerEntry> found = orderId == null ? Optional.empty() : repo.findByOrderId(orderId);
        if (found.isEmpty()) return new Result(true, "ignored: not a Leash order", null);
        LedgerEntry e = found.get();
        if (e.status() != EntryStatus.AWAITING_PAYER) return new Result(true, "no change: entry is " + e.status(), e.id());
        try {
            LedgerEntry after = payments.capture(e.id());
            return new Result(true, "captured: " + after.status(), e.id());
        } catch (IllegalStateException ex) {
            return new Result(true, "not captured: " + ex.getMessage(), e.id());
        }
    }

    /** Money moved. Confirms an entry still waiting on PayPal, if the amount matches. resource = capture. */
    private Result captureCompleted(Map<String, Object> capture) {
        String captureId = str(capture.get("id"));
        Optional<LedgerEntry> found = entryForCapture(captureId, capture);
        if (found.isEmpty()) return new Result(true, "ignored: not a Leash payment", null);
        LedgerEntry e = found.get();
        if (e.status() == EntryStatus.PAID || e.status() == EntryStatus.REFUNDED) {
            return new Result(true, "no change: already " + e.status(), e.id());
        }
        if (e.status() != EntryStatus.AWAITING_PAYER && e.status() != EntryStatus.PROCESSING) {
            return new Result(true, "no change: entry is " + e.status(), e.id());
        }
        if (!amountMatches(e, capture)) {
            log.warn("Webhook capture {} amount doesn't match entry {}; not marking it paid", captureId, e.id());
            return new Result(true, "ignored: amount doesn't match the purchase", e.id());
        }
        LedgerEntry next = e.withPayPal(null, captureId, null, ledger.now())
                .withStatus(EntryStatus.PAID, "Paid via PayPal sandbox (confirmed by PayPal webhook).", ledger.now());
        return ledger.updateIf(next, e.status())
                .map(x -> new Result(true, "marked paid", e.id()))
                .orElseGet(() -> new Result(true, "no change: entry moved meanwhile", e.id()));
    }

    /** PayPal refused the money after all. resource = capture. */
    private Result captureDenied(Map<String, Object> capture) {
        Optional<LedgerEntry> found = entryForCapture(str(capture.get("id")), capture);
        if (found.isEmpty()) return new Result(true, "ignored: not a Leash payment", null);
        LedgerEntry e = found.get();
        if (e.status() != EntryStatus.PAID && e.status() != EntryStatus.AWAITING_PAYER && e.status() != EntryStatus.PROCESSING) {
            return new Result(true, "no change: entry is " + e.status(), e.id());
        }
        LedgerEntry next = e.withStatus(EntryStatus.PAYMENT_FAILED, "PayPal denied the payment (PayPal webhook). Nothing was paid.", ledger.now());
        return ledger.updateIf(next, e.status())
                .map(x -> new Result(true, "marked payment failed", e.id()))
                .orElseGet(() -> new Result(true, "no change: entry moved meanwhile", e.id()));
    }

    /**
     * A refund went through: from Leash's Regret button or straight from the PayPal dashboard. A full refund marks the
     * entry REFUNDED (frees the length); a partial one is noted but the entry stays PAID. resource = refund.
     */
    private Result captureRefunded(Map<String, Object> refund) {
        String refundId = str(refund.get("id"));
        String captureId = linkId(refund, CAPTURE_LINK);
        if (captureId == null) captureId = relatedId(refund, "capture_id");
        Optional<LedgerEntry> found = captureId == null ? Optional.empty() : repo.findByCaptureId(captureId);
        if (found.isEmpty()) return new Result(true, "ignored: not a Leash payment", null);
        LedgerEntry e = found.get();
        if (e.status() == EntryStatus.REFUNDED) return new Result(true, "no change: already refunded", e.id());
        if (e.status() != EntryStatus.PAID) return new Result(true, "no change: entry is " + e.status(), e.id());
        String full = Money.format(e.request().amountMinor(), e.request().currency());
        LedgerEntry next;
        if (amountMatches(e, refund) || amountValue(refund) == null) {
            next = e.withRefund(refundId, ledger.now()).withStatus(EntryStatus.REFUNDED,
                    "Refunded " + full + " in PayPal (confirmed by PayPal webhook). The length is freed.", ledger.now());
        } else {
            next = e.withRefund(refundId, ledger.now()).withStatus(EntryStatus.PAID,
                    "Partly refunded in PayPal (" + amountValue(refund) + " of " + full + ").", ledger.now());
        }
        return ledger.updateIf(next, EntryStatus.PAID)
                .map(x -> new Result(true, x.status() == EntryStatus.REFUNDED ? "marked refunded" : "noted partial refund", e.id()))
                .orElseGet(() -> new Result(true, "no change: entry moved meanwhile", e.id()));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    /** By capture ID first; else by the order the capture belongs to (the capture ID isn't stored until we see it). */
    private Optional<LedgerEntry> entryForCapture(String captureId, Map<String, Object> capture) {
        if (captureId != null) {
            var byCapture = repo.findByCaptureId(captureId);
            if (byCapture.isPresent()) return byCapture;
        }
        String orderId = linkId(capture, ORDER_LINK);
        if (orderId == null) orderId = relatedId(capture, "order_id");
        return orderId == null ? Optional.empty() : repo.findByOrderId(orderId);
    }

    private static boolean amountMatches(LedgerEntry e, Map<String, Object> resource) {
        String value = amountValue(resource);
        String currency = resource.get("amount") instanceof Map<?, ?> a ? str(a.get("currency_code")) : null;
        return value != null && value.equals(Money.toPayPalValue(e.request().amountMinor(), e.request().currency()))
                && (currency == null || currency.equals(e.request().currency()));
    }

    private static String amountValue(Map<String, Object> resource) {
        return resource.get("amount") instanceof Map<?, ?> a ? str(a.get("value")) : null;
    }

    private static String linkId(Map<String, Object> resource, Pattern p) {
        if (resource.get("links") instanceof List<?> links) {
            for (Object o : links) {
                if (o instanceof Map<?, ?> l && "up".equals(l.get("rel")) && str(l.get("href")) instanceof String href) {
                    Matcher m = p.matcher(href);
                    if (m.find()) return m.group(1);
                }
            }
        }
        return null;
    }

    private static String relatedId(Map<String, Object> resource, String key) {
        if (resource.get("supplementary_data") instanceof Map<?, ?> sd && sd.get("related_ids") instanceof Map<?, ?> ids) {
            return str(ids.get(key));
        }
        return null;
    }

    private boolean seen(String eventId) {
        return jdbc.sql("SELECT COUNT(*) FROM webhook_events WHERE id = ?").param(eventId).query(Integer.class).single() > 0;
    }

    private void record(String eventId, String type, String resourceId, Result r) {
        try {
            jdbc.sql("INSERT INTO webhook_events (id, event_type, resource_id, entry_id, result, received_at) VALUES (?, ?, ?, ?, ?, ?)")
                    .params(eventId, type, truncate(resourceId, 80), r.entryId(), truncate(r.result(), 200), Db.ts(ledger.now()))
                    .update();
        } catch (DuplicateKeyException ignored) {
            // Two deliveries of one event at once: both handlers were state-conditional, so applying twice was a no-op.
        }
    }

    private static String str(Object o) {
        return o instanceof String s ? s : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
