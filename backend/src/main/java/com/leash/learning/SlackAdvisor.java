package com.leash.learning;

import com.leash.policy.Mandate;

import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Mandate learning: from how the human answered tugs, should the slack (auto-approve threshold) go up?
 *
 * <p>Pure and deterministic: the number always comes from here, never from a model. The rules, all conservative:
 * <ul>
 *   <li>Evidence for a raise is a tug the human <em>approved</em> whose <em>only</em> reason was being over the slack. A
 *       tug for quiet hours, a new shop or a risk signal says nothing about the amount the human is happy with.</li>
 *   <li>Any <em>denied</em> tug above the slack counts against, whatever its reason: if the human said no to £28, a
 *       raise to £30 would have auto-approved something they refused.</li>
 *   <li>The new slack is a round number ({@link #roundUp}) covering at least {@link #MIN_APPROVALS} approvals, with no
 *       denial anywhere between the old slack and the new one. The smallest such step is suggested, so the leash loosens
 *       a little at a time.</li>
 *   <li>Never above the length (an auto-approve the length forbids anyway is meaningless), nor above the per-purchase
 *       cap when there is one. Same currency as the mandate only.</li>
 * </ul>
 */
public final class SlackAdvisor {

    public static final int MIN_APPROVALS = 3;

    private SlackAdvisor() {}

    /** One answered tug, as the advisor sees it. {@code onlyOverSlack}: OVER_SLACK was its single reason. */
    public record AnsweredTug(long amountMinor, String currency, String category, boolean approved, boolean onlyOverSlack) {}

    /** A suggested raise and the evidence behind it. Amounts in minor units of {@code currency}. */
    public record Suggestion(long fromMinor, long toMinor, String currency, int approvals,
                             long lowestApprovedMinor, long highestApprovedMinor, String category) {}

    public static Optional<Suggestion> suggest(Mandate m, List<AnsweredTug> history) {
        long slack = m.autoApproveUpToMinor();
        long ceiling = m.perPurchaseMaxMinor() > 0 ? Math.min(m.periodLimitMinor(), m.perPurchaseMaxMinor()) : m.periodLimitMinor();
        if (ceiling <= slack) return Optional.empty();

        List<AnsweredTug> same = history.stream().filter(t -> m.currency().equalsIgnoreCase(t.currency())).toList();
        List<AnsweredTug> approvals = same.stream()
                .filter(t -> t.approved() && t.onlyOverSlack() && t.amountMinor() > slack && t.amountMinor() <= ceiling)
                .toList();
        if (approvals.size() < MIN_APPROVALS) return Optional.empty();

        // Nothing at or above the lowest denial (over the slack) may become automatic.
        long lowestDenial = same.stream().filter(t -> !t.approved() && t.amountMinor() > slack)
                .mapToLong(AnsweredTug::amountMinor).min().orElse(Long.MAX_VALUE);

        var candidates = new TreeSet<Long>();
        for (AnsweredTug t : approvals) candidates.add(Math.min(roundUp(t.amountMinor()), ceiling));
        for (long to : candidates) {
            if (to <= slack) continue;
            if (to >= lowestDenial) break; // every larger step would include the denial too
            List<AnsweredTug> covered = approvals.stream().filter(t -> t.amountMinor() <= to).toList();
            if (covered.size() < MIN_APPROVALS) continue;
            long lo = covered.stream().mapToLong(AnsweredTug::amountMinor).min().orElseThrow();
            long hi = covered.stream().mapToLong(AnsweredTug::amountMinor).max().orElseThrow();
            return Optional.of(new Suggestion(slack, to, m.currency(), covered.size(), lo, hi, mostCommonCategory(covered)));
        }
        return Optional.empty();
    }

    /**
     * Rounds up to a number a person would pick: whole £5 under £50, £10 under £200, £50 under £1,000, then £100.
     * Exact multiples stay put (£30.00 → £30.00).
     */
    static long roundUp(long minor) {
        long step = minor < 5_000 ? 500 : minor < 20_000 ? 1_000 : minor < 100_000 ? 5_000 : 10_000;
        return ((minor + step - 1) / step) * step;
    }

    private static String mostCommonCategory(List<AnsweredTug> tugs) {
        return tugs.stream()
                .filter(t -> t.category() != null && !t.category().isBlank())
                .collect(java.util.stream.Collectors.groupingBy(t -> t.category().toLowerCase(java.util.Locale.ROOT),
                        java.util.TreeMap::new, java.util.stream.Collectors.counting()))
                .entrySet().stream()
                .max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey)
                .orElse(null);
    }
}
