package com.leash.policy;

import java.time.LocalTime;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The rules an agent spends under. Money is always minor units (pence/cents) plus an ISO currency: never doubles.
 *
 * <p>Empty allow-sets mean "anything not blocked". Matching is case-insensitive.
 *
 * @param periodLimitMinor     the length: most the agent may spend per period (approved + pending tugs)
 * @param autoApproveUpToMinor the slack: purchases at or below this need no human
 * @param perPurchaseMaxMinor  no single purchase may exceed this, even with approval (0 = no cap)
 * @param maxPurchasesPerHour  velocity limit (0 = no limit)
 * @param quietStart           purchases between quietStart and quietEnd always tug (both null = off)
 */
public record Mandate(
        String id,
        String agentId,
        String name,
        String currency,
        Period period,
        long periodLimitMinor,
        long autoApproveUpToMinor,
        long perPurchaseMaxMinor,
        Set<String> allowedMerchants,
        Set<String> blockedMerchants,
        Set<String> allowedCategories,
        Set<String> blockedCategories,
        int maxPurchasesPerHour,
        LocalTime quietStart,
        LocalTime quietEnd,
        boolean active) {

    public Mandate {
        allowedMerchants = normalise(allowedMerchants);
        blockedMerchants = normalise(blockedMerchants);
        allowedCategories = normalise(allowedCategories);
        blockedCategories = normalise(blockedCategories);
        currency = currency == null ? null : currency.toUpperCase(Locale.ROOT);
    }

    static Set<String> normalise(Set<String> values) {
        if (values == null) return Set.of();
        return values.stream()
                .filter(v -> v != null && !v.isBlank())
                .map(v -> v.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    public boolean hasQuietHours() {
        return quietStart != null && quietEnd != null;
    }

    /** Handles windows that cross midnight, e.g. 23:00 to 06:00. */
    public boolean isQuiet(LocalTime t) {
        if (!hasQuietHours()) return false;
        if (quietStart.isBefore(quietEnd)) {
            return !t.isBefore(quietStart) && t.isBefore(quietEnd);
        }
        return !t.isBefore(quietStart) || t.isBefore(quietEnd);
    }
}
