package com.leash.learning;

import com.leash.learning.SlackAdvisor.AnsweredTug;
import com.leash.policy.Mandate;
import com.leash.policy.Period;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Mandate learning stats: when a slack raise is suggested, to what, and every case where it must not be. */
class SlackAdvisorTest {

    /** £60 a week, auto-approve up to £25, per-purchase cap £60: the demo grocery leash. */
    static Mandate leash(long length, long slack, long cap) {
        return new Mandate("m", "agent", "Groceries", "GBP", Period.WEEK, length, slack, cap, Set.of(), Set.of(),
                Set.of(), Set.of(), 0, null, null, true);
    }

    static final Mandate GROCERY = leash(6_000, 2_500, 6_000);

    static AnsweredTug yes(long amount) {
        return new AnsweredTug(amount, "GBP", "Groceries", true, true);
    }

    static AnsweredTug no(long amount) {
        return new AnsweredTug(amount, "GBP", "Groceries", false, true);
    }

    static List<AnsweredTug> tugs(AnsweredTug... t) {
        return new ArrayList<>(List.of(t));
    }

    @Test
    void fourApprovalsJustOverTheSlackSuggestTheNextRoundNumber() {
        var s = SlackAdvisor.suggest(GROCERY, tugs(yes(2_650), yes(2_799), yes(2_900), yes(2_740))).orElseThrow();
        assertThat(s.fromMinor()).isEqualTo(2_500);
        assertThat(s.toMinor()).isEqualTo(3_000);
        assertThat(s.approvals()).isEqualTo(4);
        assertThat(s.lowestApprovedMinor()).isEqualTo(2_650);
        assertThat(s.highestApprovedMinor()).isEqualTo(2_900);
        assertThat(s.category()).isEqualTo("groceries");
        assertThat(s.currency()).isEqualTo("GBP");
    }

    @Test
    void exactlyThreeApprovalsIsEnough() {
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700), yes(2_800)))).isPresent();
    }

    @Test
    void twoApprovalsIsNotEnough() {
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700)))).isEmpty();
    }

    @Test
    void noHistoryNoSuggestion() {
        assertThat(SlackAdvisor.suggest(GROCERY, List.of())).isEmpty();
    }

    @Test
    void aDenialInsideTheRangeStopsIt() {
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700), yes(2_800), no(2_750)))).isEmpty();
    }

    @Test
    void aDenialExactlyAtTheNewSlackStopsIt() {
        // Raising to £30 would auto-approve the £30 the human refused.
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700), yes(2_800), no(3_000)))).isEmpty();
    }

    @Test
    void aDenialBetweenTheOldSlackAndTheApprovalsStopsIt() {
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(no(2_550), yes(2_600), yes(2_700), yes(2_800)))).isEmpty();
    }

    @Test
    void aDenialAboveTheNewSlackDoesNotMatter() {
        var s = SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700), yes(2_800), no(4_500)));
        assertThat(s).map(SlackAdvisor.Suggestion::toMinor).contains(3_000L);
    }

    @Test
    void aDenialBelowTheSlackIsNotInTheRange() {
        // e.g. a £20 basket tugged for quiet hours and refused: says nothing about £25 to £30.
        var s = SlackAdvisor.suggest(GROCERY, tugs(new AnsweredTug(2_000, "GBP", "Groceries", false, false),
                yes(2_600), yes(2_700), yes(2_800)));
        assertThat(s).map(SlackAdvisor.Suggestion::toMinor).contains(3_000L);
    }

    @Test
    void aDenialBlocksEveryLargerStepToo() {
        // Two approvals under £30, a denial at £33, three approvals at £36 to £38: £30 lacks evidence and £40 would
        // include the £33 refusal, so nothing.
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700), no(3_300), yes(3_600), yes(3_700), yes(3_800))))
                .isEmpty();
    }

    @Test
    void aDenialAboveAnEarlierSufficientStepStillAllowsThatStep() {
        var s = SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700), yes(2_800), no(3_300), yes(3_600), yes(3_700)));
        assertThat(s).map(SlackAdvisor.Suggestion::toMinor).contains(3_000L);
    }

    @Test
    void picksTheSmallestStepThatHasEnoughEvidence() {
        var s = SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(2_700), yes(2_800), yes(4_100), yes(4_200), yes(4_300)))
                .orElseThrow();
        assertThat(s.toMinor()).isEqualTo(3_000);
        assertThat(s.approvals()).isEqualTo(3);
        assertThat(s.highestApprovedMinor()).isEqualTo(2_800);
    }

    @Test
    void spreadOutApprovalsTakeALargerStep() {
        var s = SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), yes(3_300), yes(3_800))).orElseThrow();
        assertThat(s.toMinor()).isEqualTo(4_000);
        assertThat(s.approvals()).isEqualTo(3);
    }

    @Test
    void neverAboveTheLength() {
        // Length £28: rounding would say £30, the length caps it.
        var s = SlackAdvisor.suggest(leash(2_800, 2_500, 0), tugs(yes(2_600), yes(2_700), yes(2_750))).orElseThrow();
        assertThat(s.toMinor()).isEqualTo(2_800);
    }

    @Test
    void neverAboveThePerPurchaseCap() {
        var s = SlackAdvisor.suggest(leash(6_000, 2_500, 2_700), tugs(yes(2_600), yes(2_650), yes(2_700))).orElseThrow();
        assertThat(s.toMinor()).isEqualTo(2_700);
    }

    @Test
    void nothingWhenTheSlackAlreadyEqualsTheLength() {
        assertThat(SlackAdvisor.suggest(leash(2_500, 2_500, 0), tugs(yes(2_600), yes(2_700), yes(2_800)))).isEmpty();
    }

    @Test
    void approvalsAboveTheCeilingAreNotEvidence() {
        // Cap £27: the £28 and £29 approvals can't become automatic, so only one approval remains.
        assertThat(SlackAdvisor.suggest(leash(6_000, 2_500, 2_700), tugs(yes(2_600), yes(2_800), yes(2_900)))).isEmpty();
    }

    @Test
    void tugsWithOtherReasonsAreNotEvidence() {
        var quiet = new AnsweredTug(2_700, "GBP", "Groceries", true, false); // over slack AND in quiet hours
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), quiet, yes(2_800)))).isEmpty();
    }

    @Test
    void approvalsAtOrBelowTheCurrentSlackAreIgnored() {
        // From before an earlier raise: they're already automatic.
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_200), yes(2_500), yes(2_600), yes(2_700)))).isEmpty();
    }

    @Test
    void otherCurrenciesAreIgnored() {
        var eur = new AnsweredTug(2_700, "EUR", "Groceries", true, true);
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), eur, yes(2_800)))).isEmpty();
        var eurDenial = new AnsweredTug(2_700, "EUR", "Groceries", false, true);
        assertThat(SlackAdvisor.suggest(GROCERY, tugs(yes(2_600), eurDenial, yes(2_800), yes(2_900)))).isPresent();
    }

    @Test
    void mostCommonCategoryNamesTheSuggestion() {
        var s = SlackAdvisor.suggest(GROCERY, tugs(yes(2_600),
                new AnsweredTug(2_700, "GBP", "Household", true, true), yes(2_800))).orElseThrow();
        assertThat(s.category()).isEqualTo("groceries");
    }

    @Test
    void roundsUpToNumbersAPersonWouldPick() {
        assertThat(SlackAdvisor.roundUp(2_501)).isEqualTo(3_000);
        assertThat(SlackAdvisor.roundUp(3_000)).isEqualTo(3_000);
        assertThat(SlackAdvisor.roundUp(4_999)).isEqualTo(5_000);
        assertThat(SlackAdvisor.roundUp(5_001)).isEqualTo(6_000);
        assertThat(SlackAdvisor.roundUp(19_999)).isEqualTo(20_000);
        assertThat(SlackAdvisor.roundUp(20_001)).isEqualTo(25_000);
        assertThat(SlackAdvisor.roundUp(99_999)).isEqualTo(100_000);
        assertThat(SlackAdvisor.roundUp(100_001)).isEqualTo(110_000);
    }
}
