package com.leash.api;

import com.leash.paypal.PayPalClient;
import com.leash.paypal.PayPalClient.PayPalOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Manual PayPal testing (T02). Create an order, approve it as the sandbox buyer, then capture it.
 * Only registered when leash.dev-endpoints=true; production sets it to false.
 */
@RestController
@RequestMapping("/api/dev/paypal")
@ConditionalOnBooleanProperty(name = "leash.dev-endpoints", matchIfMissing = true)
public class DevPayPalController {

    private final PayPalClient paypal;

    public DevPayPalController(PayPalClient paypal) {
        this.paypal = paypal;
    }

    @PostMapping("/order")
    @ResponseStatus(HttpStatus.CREATED)
    public OrderView create(@RequestBody(required = false) CreateOrder body) {
        requireConfigured();
        CreateOrder b = body == null ? new CreateOrder(null, null, null, null) : body;
        long amount = b.amountMinor() == null ? 1_000 : b.amountMinor();
        if (amount <= 0) throw new IllegalArgumentException("amountMinor must be more than zero");
        String currency = b.currency() == null || b.currency().isBlank() ? "GBP" : b.currency().toUpperCase();
        String requestId = b.requestId() == null || b.requestId().isBlank() ? "dev-" + UUID.randomUUID() : b.requestId();
        String description = b.description() == null ? "Leash dev test order" : b.description();
        return OrderView.of(paypal.createOrder(requestId, amount, currency, description), requestId);
    }

    @PostMapping("/order/{id}/capture")
    public OrderView capture(@PathVariable String id) {
        requireConfigured();
        return OrderView.of(paypal.captureOrder(id), null);
    }

    @GetMapping("/order/{id}")
    public OrderView get(@PathVariable String id) {
        requireConfigured();
        return OrderView.of(paypal.getOrder(id), null);
    }

    private void requireConfigured() {
        if (!paypal.configured()) throw new NotConfiguredException("PayPal isn't configured. Add sandbox credentials to .env.");
    }

    /** All fields optional: defaults to a £10.00 GBP order with a fresh request ID. */
    public record CreateOrder(Long amountMinor, String currency, String description, String requestId) {}

    public record OrderView(String id, String status, String approveUrl, String captureId, String captureStatus,
                            String requestId) {
        static OrderView of(PayPalOrder o, String requestId) {
            return new OrderView(o.id(), o.status(), o.approveUrl().orElse(null), o.captureId(), o.captureStatus(), requestId);
        }
    }
}
