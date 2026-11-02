package com.leash.mandate;

import com.leash.policy.Mandate;
import com.leash.policy.Period;

import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** The three leashes the demo and the video use (sam_data.md §3, use cases 1, 2 and 4). */
public final class DemoLeashes {
    private DemoLeashes() {}

    public record Seed(String agentId, String agentName, String kind, Mandate mandate, String sourceText) {}

    public static final String GROCERY_AGENT = "agent-larder";
    public static final String OFFICE_AGENT = "agent-office";
    public static final String TRAVEL_AGENT = "agent-travel";

    public static Mandate grocery() {
        return new Mandate("m-grocery", GROCERY_AGENT, "Weekly groceries", "GBP", Period.WEEK,
                6_000, 2_500, 6_000,
                Set.of("Larder", "Tesco", "Sainsbury's"), Set.of(),
                Set.of("groceries", "household"), Set.of("gift cards"),
                6, LocalTime.of(0, 0), LocalTime.of(6, 0), true);
    }

    public static Mandate office() {
        return new Mandate("m-office", OFFICE_AGENT, "Office supplies", "GBP", Period.MONTH,
                30_000, 5_000, 30_000,
                Set.of("Viking", "Staples", "Amazon Business"), Set.of(),
                Set.of("office supplies", "stationery", "furniture"), Set.of("electronics", "gift cards"),
                5, null, null, true);
    }

    public static Mandate travel() {
        return new Mandate("m-travel", TRAVEL_AGENT, "Edinburgh trip", "GBP", Period.TOTAL,
                40_000, 10_000, 25_000,
                Set.of(), Set.of(),
                Set.of("airlines", "hotels", "rail"), Set.of("gift cards"),
                4, LocalTime.of(0, 0), LocalTime.of(6, 0), true);
    }

    public static List<Seed> all() {
        return List.of(
                new Seed(GROCERY_AGENT, "Larder bot", "grocery", grocery(),
                        "Groceries, £60 a week, supermarkets only, ask me over £25. Never gift cards."),
                new Seed(OFFICE_AGENT, "Office bot", "office", office(),
                        "Office supplies for the studio, £300 a month from Viking, Staples or Amazon Business. Ask me over £50. No electronics."),
                new Seed(TRAVEL_AGENT, "Travel bot", "travel", travel(),
                        "Book the Edinburgh trip: £400 in total, airlines, hotels and rail only, ask me over £100, nothing between midnight and 6am."));
    }
}
