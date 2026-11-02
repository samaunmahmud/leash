package com.leash.policy;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The deterministic heart of Leash. No AI, no I/O, no Spring: the same inputs always give the same decision.
 *
 * <p>Every rule is checked and every violation is reported (not just the first), so the ledger can explain the
 * whole picture. The outcome is the most severe rule that fired.
 */
public final class PolicyEngine {

    private final ZoneId zone;

    public PolicyEngine(ZoneId zone) {
        this.zone = Objects.requireNonNull(zone);
    }

    public Decision evaluate(Mandate m, PurchaseRequest r, SpendSnapshot spend) {
        List<Reason> reasons = new ArrayList<>();
        String cur = m.currency();

        // Snap back beats everything: a revoked agent gets nothing else checked.
        if (spend.agentRevoked()) {
            reasons.add(new Reason(RuleCode.AGENT_REVOKED, "This agent was snapped back. It can't spend until you re-leash it."));
            return Decision.of(r.id(), reasons);
        }
        if (!m.active()) {
            reasons.add(new Reason(RuleCode.MANDATE_INACTIVE, "The mandate \"" + m.name() + "\" is paused."));
        }
        if (!Objects.equals(m.agentId(), r.agentId()) || !Objects.equals(m.id(), r.mandateId())) {
            reasons.add(new Reason(RuleCode.WRONG_MANDATE, "This agent isn't covered by the mandate it cited."));
        }
        if (r.amountMinor() <= 0) {
            reasons.add(new Reason(RuleCode.INVALID_AMOUNT, "The amount must be more than zero."));
            return Decision.of(r.id(), reasons);
        }
        if (r.currency() == null || !r.currency().equalsIgnoreCase(cur)) {
            reasons.add(new Reason(RuleCode.CURRENCY_MISMATCH,
                    "The leash is in " + cur + " but the purchase is in " + r.currency() + "."));
            return Decision.of(r.id(), reasons); // amounts below aren't comparable
        }

        String merchant = lower(r.merchant());
        String category = lower(r.category());

        if (m.blockedMerchants().contains(merchant)) {
            reasons.add(new Reason(RuleCode.MERCHANT_BLOCKED, r.merchant() + " is on the blocked list."));
        } else if (!m.allowedMerchants().isEmpty() && !m.allowedMerchants().contains(merchant)) {
            reasons.add(new Reason(RuleCode.MERCHANT_NOT_ALLOWED, r.merchant() + " isn't one of the allowed shops."));
        }
        if (m.blockedCategories().contains(category)) {
            reasons.add(new Reason(RuleCode.CATEGORY_BLOCKED, "\"" + r.category() + "\" is a blocked category."));
        } else if (!m.allowedCategories().isEmpty() && !m.allowedCategories().contains(category)) {
            reasons.add(new Reason(RuleCode.CATEGORY_NOT_ALLOWED, "\"" + r.category() + "\" isn't an allowed category."));
        }

        String amount = Money.format(r.amountMinor(), cur);
        if (m.perPurchaseMaxMinor() > 0 && r.amountMinor() > m.perPurchaseMaxMinor()) {
            reasons.add(new Reason(RuleCode.OVER_PURCHASE_MAX,
                    amount + " is over the " + Money.format(m.perPurchaseMaxMinor(), cur) + " cap for a single purchase."));
        }
        long after = Math.addExact(spend.spentInPeriodMinor(), r.amountMinor());
        if (after > m.periodLimitMinor()) {
            long left = Math.max(0, m.periodLimitMinor() - spend.spentInPeriodMinor());
            reasons.add(new Reason(RuleCode.OVER_LENGTH,
                    amount + " would go past the " + Money.format(m.periodLimitMinor(), cur) + " " + periodWord(m.period())
                            + " length (" + Money.format(left, cur) + " left)."));
        }
        if (m.maxPurchasesPerHour() > 0 && spend.purchasesLastHour() >= m.maxPurchasesPerHour()) {
            reasons.add(new Reason(RuleCode.VELOCITY_LIMIT,
                    "Already " + spend.purchasesLastHour() + " purchases in the last hour (limit " + m.maxPurchasesPerHour() + ")."));
        }

        if (r.amountMinor() > m.autoApproveUpToMinor()) {
            reasons.add(new Reason(RuleCode.OVER_SLACK,
                    amount + " is above the " + Money.format(m.autoApproveUpToMinor(), cur) + " you're happy to auto-approve."));
        }
        if (r.requestedAt() != null && m.isQuiet(LocalTime.ofInstant(r.requestedAt(), zone))) {
            reasons.add(new Reason(RuleCode.QUIET_HOURS,
                    "It's inside your quiet hours (" + m.quietStart() + "–" + m.quietEnd() + ")."));
        }

        return Decision.of(r.id(), reasons);
    }

    private static String lower(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String periodWord(Period p) {
        return switch (p) {
            case DAY -> "daily";
            case WEEK -> "weekly";
            case MONTH -> "monthly";
            case TOTAL -> "total";
        };
    }
}
