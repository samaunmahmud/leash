package com.leash.api;

import com.leash.config.LeashProperties;
import com.leash.ledger.EntryStatus;
import com.leash.purchase.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.HtmlUtils;

/**
 * Where PayPal sends the payer after approving or cancelling (the return_url / cancel_url set on each order).
 *
 * <p>return_url / cancel_url: https://developer.paypal.com/docs/api/orders/v2/#orders_create
 * (payment_source.paypal.experience_context). The order ID arriving as the {@code token} query parameter is
 * PayPal's documented redirect behaviour for Express Checkout
 * (https://developer.paypal.com/api/nvp-soap/do-express-checkout-payment-nvp/) and matches the approve link
 * (checkoutnow?token=ORDER-ID), but the Orders v2 reference doesn't spell it out: NEEDS A LIVE CHECK. That's why this
 * is only a convenience. Without {@code token} nothing is captured, and POST /api/ledger/{id}/capture still works.
 * Safety: capture only succeeds for an order the payer really approved in PayPal, so a forged visit here can't move
 * money. PayerID is ignored (payer data).
 */
@RestController
@RequestMapping("/api/paypal")
public class PayPalReturnController {

    private static final Logger log = LoggerFactory.getLogger(PayPalReturnController.class);

    private final LeashProperties props;
    private final PaymentService payments;
    private final com.leash.vault.WalletService wallet;

    public PayPalReturnController(LeashProperties props, PaymentService payments, com.leash.vault.WalletService wallet) {
        this.props = props;
        this.payments = payments;
        this.wallet = wallet;
    }

    /**
     * The owner approved saving PayPal (setup token return_url). Query parameters are ignored: Leash finishes with the
     * setup token it stored itself, and PayPal only turns it into a payment token if the owner really approved it, so
     * a forged visit can't save anything.
     */
    @GetMapping(value = "/vault/return", produces = MediaType.TEXT_HTML_VALUE)
    public String vaultApproved() {
        try {
            var v = wallet.complete();
            return page("PayPal saved", "Approved purchases will now be paid from "
                    + (v.hint() == null ? "your PayPal" : v.hint()) + " with no popup. You can close this tab.");
        } catch (RuntimeException e) {
            log.warn("Finishing PayPal save failed: {}", e.getMessage());
            return page("Not saved yet", "Leash couldn't finish saving PayPal. Go back to Leash and press \"I've approved it\".");
        }
    }

    @GetMapping(value = "/vault/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public String vaultCancelled() {
        wallet.cancel();
        return page("Not saved", "Nothing was saved. Purchases will keep asking you to approve each payment.");
    }

    @GetMapping(value = "/return", produces = MediaType.TEXT_HTML_VALUE)
    public String approved(@RequestParam(name = "token", required = false) String orderId) {
        if (orderId == null || orderId.isBlank()) {
            return page("Approved in PayPal", "You can close this tab.");
        }
        try {
            return payments.captureByOrder(orderId)
                    .map(e -> e.status() == EntryStatus.PAID
                            ? page("Paid", "Leash captured the payment and recorded it in the ledger. You can close this tab.")
                            : page("Payment problem", "PayPal didn't complete the payment. The ledger has the details."))
                    .orElseGet(() -> page("Approved in PayPal", "This order isn't a Leash purchase (dev test?). Capture it from the dev endpoint."));
        } catch (RuntimeException e) {
            log.warn("Capture on return failed for order {}: {}", orderId, e.getMessage());
            return page("Payment problem", "Leash couldn't capture the payment yet. Try the capture button in the ledger.");
        }
    }

    @GetMapping(value = "/cancel", produces = MediaType.TEXT_HTML_VALUE)
    public String cancelled(@RequestParam(name = "token", required = false) String orderId) {
        if (orderId != null && !orderId.isBlank()) payments.cancelByOrder(orderId);
        return page("Cancelled", "Nothing was paid. You can close this tab.");
    }

    String page(String title, String body) {
        String app = HtmlUtils.htmlEscape(props.appUrl());
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1"><title>Leash · %s</title>
                <style>body{font:16px/1.5 system-ui,sans-serif;background:#0e0a0c;color:#f6eff1;display:grid;place-items:center;min-height:100vh;margin:0;padding:16px}
                main{max-width:420px;border:1px solid #2a1f23;background:#171113;border-radius:16px;padding:28px}
                h1{font-size:20px;margin:0 0 8px}p{color:#c9bcc0;margin:0 0 16px}a{color:#e8668c}</style></head>
                <body><main><h1>%s</h1><p>%s</p><a href="%s/#/app">Back to Leash</a></main></body></html>
                """.formatted(HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(title), HtmlUtils.htmlEscape(body), app);
    }
}
