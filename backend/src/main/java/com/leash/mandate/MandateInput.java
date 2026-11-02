package com.leash.mandate;

import com.leash.policy.Mandate;
import com.leash.policy.Period;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalTime;
import java.util.Set;

/**
 * A mandate as the dashboard (or the AI parser) sends it. Validated before it becomes a domain {@link Mandate}: the
 * AI parser's output goes through exactly the same checks, so a model can never save a nonsensical leash.
 */
public record MandateInput(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}", message = "must be a 3-letter ISO currency") String currency,
        @NotNull Period period,
        @Positive long periodLimitMinor,
        @PositiveOrZero long autoApproveUpToMinor,
        @PositiveOrZero long perPurchaseMaxMinor,
        Set<@Size(max = 80) String> allowedMerchants,
        Set<@Size(max = 80) String> blockedMerchants,
        Set<@Size(max = 80) String> allowedCategories,
        Set<@Size(max = 80) String> blockedCategories,
        @Min(0) @Max(1000) int maxPurchasesPerHour,
        @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "must be HH:mm") String quietStart,
        @Pattern(regexp = "([01]\\d|2[0-3]):[0-5]\\d", message = "must be HH:mm") String quietEnd,
        Boolean active,
        @Size(max = 2000) String sourceText) {

    @AssertTrue(message = "slack (auto-approve) can't be more than the length")
    public boolean isSlackWithinLength() {
        return autoApproveUpToMinor <= periodLimitMinor;
    }

    @AssertTrue(message = "quiet hours need both a start and an end")
    public boolean isQuietHoursComplete() {
        return (quietStart == null || quietStart.isBlank()) == (quietEnd == null || quietEnd.isBlank());
    }

    public Mandate toMandate(String id, String agentId) {
        return new Mandate(id, agentId, name.trim(), currency, period, periodLimitMinor, autoApproveUpToMinor,
                perPurchaseMaxMinor, allowedMerchants, blockedMerchants, allowedCategories, blockedCategories,
                maxPurchasesPerHour, time(quietStart), time(quietEnd), active == null || active);
    }

    public static MandateInput from(Mandate m, String sourceText) {
        return new MandateInput(m.name(), m.currency(), m.period(), m.periodLimitMinor(), m.autoApproveUpToMinor(),
                m.perPurchaseMaxMinor(), m.allowedMerchants(), m.blockedMerchants(), m.allowedCategories(),
                m.blockedCategories(), m.maxPurchasesPerHour(),
                m.quietStart() == null ? null : m.quietStart().toString(),
                m.quietEnd() == null ? null : m.quietEnd().toString(), m.active(), sourceText);
    }

    private static LocalTime time(String s) {
        return s == null || s.isBlank() ? null : LocalTime.parse(s);
    }
}
