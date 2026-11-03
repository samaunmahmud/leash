package com.leash.mandate;

import com.leash.policy.Period;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The no-AI reading, on the example mandates from the brief (§3) and the composer's examples. */
class HeuristicMandateParserTest {

    final HeuristicMandateParser parser = new HeuristicMandateParser();

    @Test
    void groceryLeash() {
        var p = parser.parse("Groceries, £60 a week, supermarkets only, ask me over £25. Never gift cards.");
        var m = p.mandate();
        assertThat(p.source()).isEqualTo("heuristic");
        assertThat(p.kind()).isEqualTo("grocery");
        assertThat(m.name()).isEqualTo("Groceries");
        assertThat(m.period()).isEqualTo(Period.WEEK);
        assertThat(m.periodLimitMinor()).isEqualTo(6_000);
        assertThat(m.autoApproveUpToMinor()).isEqualTo(2_500);
        assertThat(m.allowedCategories()).containsExactlyInAnyOrder("groceries", "household");
        assertThat(m.blockedCategories()).containsExactly("gift cards");
        assertThat(m.allowedMerchants()).isEmpty();
        assertThat(p.notes()).noneMatch(n -> n.startsWith("Not turned into a rule"));
    }

    @Test
    void officeLeashWithNamedShops() {
        var p = parser.parse("Office supplies for the studio, £300 a month from Viking, Staples or Amazon Business. "
                + "Ask me over £50. No electronics.");
        var m = p.mandate();
        assertThat(p.kind()).isEqualTo("office");
        assertThat(m.period()).isEqualTo(Period.MONTH);
        assertThat(m.periodLimitMinor()).isEqualTo(30_000);
        assertThat(m.autoApproveUpToMinor()).isEqualTo(5_000);
        assertThat(m.allowedMerchants()).containsExactly("Viking", "Staples", "Amazon Business");
        assertThat(m.allowedCategories()).contains("office supplies", "stationery");
        assertThat(m.blockedCategories()).containsExactly("electronics");
        assertThat(p.notes()).noneMatch(n -> n.startsWith("Not turned into a rule"));
    }

    @Test
    void travelLeashWithTotalBudgetAndQuietHours() {
        var p = parser.parse("Book the Edinburgh trip: £400 in total, airlines, hotels and rail only, ask me over £100, "
                + "nothing between midnight and 6am.");
        var m = p.mandate();
        assertThat(p.kind()).isEqualTo("travel");
        assertThat(m.name()).isEqualTo("Book the Edinburgh trip");
        assertThat(m.period()).isEqualTo(Period.TOTAL);
        assertThat(m.periodLimitMinor()).isEqualTo(40_000);
        assertThat(m.autoApproveUpToMinor()).isEqualTo(10_000);
        assertThat(m.allowedCategories()).containsExactlyInAnyOrder("airlines", "hotels", "rail");
        assertThat(m.quietStart()).isEqualTo("00:00");
        assertThat(m.quietEnd()).isEqualTo("06:00");
        assertThat(p.notes()).noneMatch(n -> n.startsWith("Not turned into a rule"));
    }

    @Test
    void subscriptionGuardianSaysWhatItCouldNotRead() {
        var p = parser.parse("Renew our existing software subscriptions, £200 a month, ask me before any new "
                + "subscription, never annual plans over £100.");
        var m = p.mandate();
        assertThat(m.period()).isEqualTo(Period.MONTH);
        assertThat(m.periodLimitMinor()).isEqualTo(20_000);
        assertThat(m.autoApproveUpToMinor()).isZero();
        assertThat(m.blockedCategories()).isEmpty();
        assertThat(p.notes()).anyMatch(n -> n.contains("every purchase will ask you"));
        assertThat(p.notes()).anyMatch(n -> n.contains("ask me before any new subscription"));
        assertThat(p.notes()).anyMatch(n -> n.contains("never annual plans over £100"));
    }

    @Test
    void capVelocityAndShopsAt() {
        var p = parser.parse("Gifts, £120 a month at John Lewis or Waterstones, max £30 per purchase, "
                + "ask me over £20, at most 3 purchases an hour");
        var m = p.mandate();
        assertThat(m.allowedMerchants()).containsExactly("John Lewis", "Waterstones");
        assertThat(m.perPurchaseMaxMinor()).isEqualTo(3_000);
        assertThat(m.autoApproveUpToMinor()).isEqualTo(2_000);
        assertThat(m.maxPurchasesPerHour()).isEqualTo(3);
    }

    @Test
    void pmTimesAndPenceAndThousands() {
        var p = parser.parse("Team lunches, £1,250.50 monthly, ask me over £40.75, nothing between 10pm and 7am");
        var m = p.mandate();
        assertThat(m.periodLimitMinor()).isEqualTo(125_050);
        assertThat(m.period()).isEqualTo(Period.MONTH);
        assertThat(m.autoApproveUpToMinor()).isEqualTo(4_075);
        assertThat(m.quietStart()).isEqualTo("22:00");
        assertThat(m.quietEnd()).isEqualTo("07:00");
    }

    @Test
    void missingLengthIsANoteNotAGuess() {
        var p = parser.parse("Buy printer ink when we run out");
        assertThat(p.mandate().periodLimitMinor()).isZero();
        assertThat(p.notes()).anyMatch(n -> n.contains("couldn't find a spending limit"));
    }

    @Test
    void slackAboveLengthIsLowered() {
        var p = parser.parse("Snacks, £20 a week, ask me over £50");
        assertThat(p.mandate().autoApproveUpToMinor()).isEqualTo(2_000);
        assertThat(p.notes()).anyMatch(n -> n.contains("lowered it to the length"));
    }

    @Test
    void times() {
        assertThat(HeuristicMandateParser.time("midnight")).isEqualTo("00:00");
        assertThat(HeuristicMandateParser.time("12am")).isEqualTo("00:00");
        assertThat(HeuristicMandateParser.time("12pm")).isEqualTo("12:00");
        assertThat(HeuristicMandateParser.time("6:30 pm")).isEqualTo("18:30");
        assertThat(HeuristicMandateParser.time("23:15")).isEqualTo("23:15");
        assertThat(HeuristicMandateParser.time("25:00")).isNull();
        assertThat(HeuristicMandateParser.time("13pm")).isNull();
    }
}
