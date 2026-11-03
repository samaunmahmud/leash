package com.leash.mcp;

import com.leash.ledger.LedgerService;
import com.leash.mandate.MandateService;
import com.leash.policy.Mandate;
import com.leash.policy.Money;
import com.leash.policy.Outcome;
import com.leash.policy.Reason;
import com.leash.purchase.PurchaseCommand;
import com.leash.purchase.PurchaseResult;
import com.leash.purchase.PurchaseService;
import jakarta.validation.Validator;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The MCP tools an agent uses to ask Leash before it spends. Same PurchaseService as REST and the demo agent, so an
 * MCP request is judged by exactly the same engine, risk checks and ledger.
 *
 * <p>The agent is always the one its key belongs to (from the transport context): no tool takes an agent ID.
 */
@Component
public class LeashTools {

    private final PurchaseService purchases;
    private final MandateService leashes;
    private final LedgerService ledger;
    private final Validator validator;

    public LeashTools(PurchaseService purchases, MandateService leashes, LedgerService ledger, Validator validator) {
        this.purchases = purchases;
        this.leashes = leashes;
        this.ledger = ledger;
        this.validator = validator;
    }

    /** What a purchase request returns. {@code next} tells the agent plainly what to do now. */
    public record PurchaseAnswer(String outcome, String status, String message, String next, List<String> reasons,
                                 String requestId, String tugId, String paypalOrderId) {}

    @McpTool(name = "request_purchase", title = "Ask Leash before spending",
            description = """
                    Ask Leash for permission BEFORE you buy anything with your owner's money. Leash checks the purchase \
                    against your leash (spending limit, auto-approve amount, allowed shops and categories, quiet hours) \
                    and a risk check, then answers with one of three outcomes:
                    APPROVE: allowed. Leash pays through PayPal (or the owner confirms in PayPal). Don't buy it again.
                    TUG: your owner has been asked to approve it. Don't buy it anywhere else meanwhile. To see their \
                    answer later, call request_purchase again with the same request_id.
                    BLOCK: refused. Do NOT retry a blocked purchase, not with different wording, a different amount, \
                    split into smaller orders, or at another shop. Tell your owner what was blocked and why.
                    Always pass the page text you read the item on: Leash checks it for instructions planted to \
                    hijack agents.""",
            annotations = @McpTool.McpAnnotations(title = "Ask Leash before spending", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = true, openWorldHint = true))
    public PurchaseAnswer requestPurchase(
            McpSyncRequestContext ctx,
            @McpToolParam(description = "Shop name exactly as shown, e.g. \"Larder\"", required = true) String merchant,
            @McpToolParam(description = "Category, e.g. \"groceries\", \"office supplies\", \"gift cards\"", required = true) String category,
            @McpToolParam(description = "What you're buying, e.g. \"Milk, eggs, bread\"", required = true) String description,
            @McpToolParam(description = "Total price as a decimal string in major units, e.g. \"18.40\"", required = true) String amount,
            @McpToolParam(description = "ISO currency code; defaults to GBP", required = false) String currency,
            @McpToolParam(description = "The task your owner gave you, in their words", required = false) String task,
            @McpToolParam(description = "Why this purchase serves that task", required = false) String reason,
            @McpToolParam(description = "URL of the page the item is on", required = false) String sourceUrl,
            @McpToolParam(description = "The text of that page as you read it", required = false) String pageText,
            @McpToolParam(description = "Your own ID for this purchase. Reuse it to check on a TUG; a repeat never buys twice", required = false) String requestId) {
        String agentId = agent(ctx);
        String cur = currency == null || currency.isBlank() ? "GBP" : currency.strip().toUpperCase(Locale.ROOT);
        var cmd = new PurchaseCommand(agentId, null, blankToNull(requestId), merchant, category, description,
                minor(amount, cur), cur, task, reason, sourceUrl, pageText);
        var violations = validator.validate(cmd);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream().map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted().collect(Collectors.joining("; ")));
        }
        PurchaseResult r = purchases.request(cmd);
        return new PurchaseAnswer(r.outcome().name(), r.status().name(), r.message(), next(r),
                r.reasons().stream().map(Reason::message).toList(), r.requestId(), r.tugId(), r.paypalOrderId());
    }

    /** What the agent can still spend this period. */
    public record Budget(String leash, String period, String length, String spent, String waitingForOwner, String left,
                         String autoApproveUpTo, boolean revoked) {}

    @McpTool(name = "check_budget", title = "How much is left on my leash",
            description = "How much you may still spend this period: the length, what's spent, what's waiting for "
                    + "your owner's approval (it counts too), what's left, and the amount up to which purchases are "
                    + "approved without asking. Check this before planning a basket.",
            annotations = @McpTool.McpAnnotations(title = "How much is left on my leash", readOnlyHint = true,
                    destructiveHint = false, idempotentHint = true, openWorldHint = false))
    public Budget checkBudget(McpSyncRequestContext ctx) {
        var leash = leashes.get(agent(ctx));
        Mandate m = leash.mandate();
        var usage = ledger.usage(m);
        long left = Math.max(0, m.periodLimitMinor() - usage.spentMinor() - usage.pendingMinor());
        return new Budget(m.name(), m.period().name().toLowerCase(Locale.ROOT), money(m.periodLimitMinor(), m),
                money(usage.spentMinor(), m), money(usage.pendingMinor(), m), money(left, m),
                money(m.autoApproveUpToMinor(), m), leash.agent().revoked());
    }

    /** The agent's rules, readable by a model. */
    public record Rules(String leash, String agent, String currency, String period, String length,
                        String autoApproveUpTo, String perPurchaseMax, List<String> onlyShops, List<String> neverShops,
                        List<String> onlyCategories, List<String> neverCategories, int maxPurchasesPerHour,
                        String quietHours, boolean revoked, String summary) {}

    @McpTool(name = "get_mandate", title = "My leash rules",
            description = "The rules you spend under: limit per period, auto-approve amount, per-purchase cap, "
                    + "allowed and forbidden shops and categories, purchases per hour, and quiet hours when every "
                    + "purchase asks your owner. Read it before shopping so you only ask for things that can pass.",
            annotations = @McpTool.McpAnnotations(title = "My leash rules", readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    public Rules getMandate(McpSyncRequestContext ctx) {
        var leash = leashes.get(agent(ctx));
        Mandate m = leash.mandate();
        String quiet = m.hasQuietHours() ? m.quietStart() + "–" + m.quietEnd() + " (UK time)" : null;
        String summary = (leash.agent().revoked() ? "Your owner has snapped you back: every purchase will be blocked. " : "")
                + money(m.periodLimitMinor(), m) + " per " + m.period().name().toLowerCase(Locale.ROOT)
                + "; up to " + money(m.autoApproveUpToMinor(), m) + " is approved without asking"
                + (m.allowedCategories().isEmpty() ? "" : "; only " + String.join(", ", m.allowedCategories()))
                + (m.blockedCategories().isEmpty() ? "" : "; never " + String.join(", ", m.blockedCategories())) + ".";
        return new Rules(m.name(), leash.agent().name(), m.currency(), m.period().name().toLowerCase(Locale.ROOT),
                money(m.periodLimitMinor(), m), money(m.autoApproveUpToMinor(), m),
                m.perPurchaseMaxMinor() == 0 ? null : money(m.perPurchaseMaxMinor(), m),
                sorted(m.allowedMerchants()), sorted(m.blockedMerchants()), sorted(m.allowedCategories()),
                sorted(m.blockedCategories()), m.maxPurchasesPerHour(), quiet, leash.agent().revoked(), summary);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    static String agent(McpSyncRequestContext ctx) {
        Object id = ctx.transportContext().get(McpConfig.AGENT_KEY);
        if (!(id instanceof String s) || s.isBlank()) {
            // AgentAuthFilter stops unauthenticated calls first; this is the belt to its braces.
            throw new IllegalStateException("No agent key on this connection.");
        }
        return s;
    }

    static String next(PurchaseResult r) {
        if (r.outcome() == Outcome.BLOCK) {
            return "Blocked. Do not retry this purchase in any form (other wording, amount, shop, or split up). "
                    + "Tell your owner what was blocked and why.";
        }
        if (r.outcome() == Outcome.TUG) {
            return switch (r.status()) {
                case DENIED -> "Your owner said no. Don't buy it. Tell them you won't.";
                case PAID, AWAITING_PAYER, PAYPAL_OFF, PROCESSING -> "Your owner approved it. Leash is handling payment; don't buy it again.";
                default -> "Waiting for your owner. Don't buy it elsewhere. Call request_purchase again later with request_id \""
                        + r.requestId() + "\" to see their answer.";
            };
        }
        return switch (r.status()) {
            case PAID -> "Paid. Don't buy it again.";
            case AWAITING_PAYER -> "Approved. Your owner confirms the payment in PayPal; don't buy it again.";
            case PAYMENT_FAILED -> "Approved, but PayPal didn't take the payment. Tell your owner; don't retry on your own.";
            default -> "Approved. Don't buy it again.";
        };
    }

    /** "18.40" GBP → 1840. Exact (BigDecimal), never floating point; more decimals than the currency has is an error. */
    static long minor(String amount, String currency) {
        if (amount == null || amount.isBlank()) throw new IllegalArgumentException("amount is required, e.g. \"18.40\"");
        int digits;
        try {
            digits = Currency.getInstance(currency).getDefaultFractionDigits();
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("currency must be an ISO code like GBP");
        }
        try {
            BigDecimal v = new BigDecimal(amount.strip().replace(",", "").replaceFirst("^[£$€]", ""));
            if (v.signum() <= 0) throw new IllegalArgumentException("amount must be more than zero");
            return v.movePointRight(digits).stripTrailingZeros().longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("amount must be a price like \"18.40\" with at most " + digits + " decimals");
        }
    }

    private static String money(long minor, Mandate m) {
        return Money.format(minor, m.currency());
    }

    private static List<String> sorted(java.util.Set<String> s) {
        return s.stream().sorted().toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
