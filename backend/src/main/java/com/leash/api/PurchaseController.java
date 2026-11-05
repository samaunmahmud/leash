package com.leash.api;

import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerService;
import com.leash.purchase.PaymentService;
import com.leash.purchase.PurchaseCommand;
import com.leash.purchase.PurchaseResult;
import com.leash.purchase.PurchaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The agent-facing endpoint (POST /api/purchases) and the human-facing ledger. */
@RestController
public class PurchaseController {

    private final PurchaseService purchases;
    private final LedgerService ledger;
    private final PaymentService payments;

    public PurchaseController(PurchaseService purchases, LedgerService ledger, PaymentService payments) {
        this.purchases = purchases;
        this.ledger = ledger;
        this.payments = payments;
    }

    /** 201 for a new decision; 200 when a repeated requestId returns the stored one. Blocks are still 201: a decision was made. */
    @PostMapping("/api/purchases")
    public ResponseEntity<PurchaseResult> request(@Valid @RequestBody PurchaseCommand cmd,
                                                  jakarta.servlet.http.HttpServletRequest http) {
        // With an agent key the key decides the leash; a different agentId in the body is refused, not ignored.
        if (http.getAttribute(com.leash.mcp.AgentAuthFilter.AGENT_ATTR) instanceof String keyAgent) {
            if (cmd.agentId() != null && !cmd.agentId().isBlank() && !cmd.agentId().equals(keyAgent)) {
                throw new ForbiddenException("This agent key belongs to another agent. An agent can only spend under its own leash.");
            }
            cmd = cmd.withAgent(keyAgent);
        }
        if (cmd.agentId() == null || cmd.agentId().isBlank()) throw new IllegalArgumentException("agentId is required");
        PurchaseResult result = purchases.request(cmd);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    @GetMapping("/api/ledger")
    public List<LedgerEntry> ledger(@RequestParam(required = false) String agentId,
                                    @RequestParam(defaultValue = "200") int limit) {
        return ledger.list(agentId, limit);
    }

    @GetMapping("/api/ledger/{id}")
    public LedgerEntry entry(@PathVariable String id) {
        return ledger.get(id);
    }

    /** Captures an approved order by hand, if the PayPal redirect didn't come back (e.g. the tab was closed). */
    @PostMapping("/api/ledger/{id}/capture")
    public LedgerEntry capture(@PathVariable String id) {
        return payments.capture(id);
    }

    /** "Regret": refunds a paid purchase in full via PayPal. 409 if it isn't paid (or PayPal refused the refund). */
    @PostMapping("/api/ledger/{id}/refund")
    public LedgerEntry refund(@PathVariable String id) {
        return payments.refund(id);
    }
}
