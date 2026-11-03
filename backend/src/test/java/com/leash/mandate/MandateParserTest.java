package com.leash.mandate;

import com.leash.llm.LlmBudget;
import com.leash.llm.LlmException;
import com.leash.llm.LlmTransport;
import com.leash.llm.StructuredLlm;
import com.leash.policy.Period;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The AI path with a fake model, and every way it falls back to the rule-based reading. */
class MandateParserTest {

    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    static final String GROCERY = "Groceries, £60 a week, supermarkets only, ask me over £25. Never gift cards.";

    final List<String> prompts = new ArrayList<>();

    MandateParser parser(String... replies) {
        LlmTransport t = (s, u) -> {
            prompts.add(u);
            return replies[Math.min(prompts.size() - 1, replies.length - 1)];
        };
        return parser(t);
    }

    static MandateParser parser(LlmTransport t) {
        var llm = new StructuredLlm(t, new LlmBudget(100, Clock.fixed(Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC)), VALIDATOR);
        return new MandateParser(llm, VALIDATOR);
    }

    static String json(String length, String slack, String extra) {
        return """
                {"name":"Weekly groceries","agentName":"Grocery bot","kind":"Grocery","currency":"gbp","period":"WEEK",
                 "length":%s,"autoApproveUpTo":%s,"perPurchaseMax":0,
                 "allowedMerchants":[],"blockedMerchants":[],"allowedCategories":["Groceries","household"],
                 "blockedCategories":["gift cards"],"maxPurchasesPerHour":0,"quietStart":null,"quietEnd":null,
                 "notes":["Supermarkets read as groceries and household."]%s}""".formatted(length, slack, extra);
    }

    @Test
    void readsTheModelsMandateIntoMinorUnits() {
        var p = parser(json("\"60.00\"", "25", "")).parse(GROCERY);
        var m = p.mandate();
        assertThat(p.source()).isEqualTo("ai");
        assertThat(p.kind()).isEqualTo("grocery");
        assertThat(p.agentName()).isEqualTo("Grocery bot");
        assertThat(m.currency()).isEqualTo("GBP");
        assertThat(m.period()).isEqualTo(Period.WEEK);
        assertThat(m.periodLimitMinor()).isEqualTo(6_000);
        assertThat(m.autoApproveUpToMinor()).isEqualTo(2_500);
        assertThat(m.allowedCategories()).containsExactlyInAnyOrder("groceries", "household");
        assertThat(m.sourceText()).isEqualTo(GROCERY);
        assertThat(p.notes()).containsExactly("Supermarkets read as groceries and household.");
        assertThat(prompts.getFirst()).contains("<owner_text>\n" + GROCERY + "\n</owner_text>");
    }

    @Test
    void decimalAmountsStayExact() {
        var m = parser(json("0.29", "0.07", "")).parse(GROCERY).mandate();
        assertThat(m.periodLimitMinor()).isEqualTo(29);   // 0.29 as a double would be 28.999…
        assertThat(m.autoApproveUpToMinor()).isEqualTo(7);
    }

    @Test
    void slackOverLengthIsSentBackOnceThenAccepted() {
        var p = parser(json("60", "90", ""), json("60", "25", "")).parse(GROCERY);
        assertThat(p.source()).isEqualTo("ai");
        assertThat(p.mandate().autoApproveUpToMinor()).isEqualTo(2_500);
        assertThat(prompts.get(1)).contains("slack (auto-approve) can't be more than the length");
    }

    @Test
    void fractionsOfAPennyAreRejected() {
        parser(json("60.005", "25", ""), json("60", "25", "")).parse(GROCERY);
        assertThat(prompts.get(1)).contains("length must be an amount with at most 2 decimal places");
    }

    @Test
    void badQuietHoursAreRejected() {
        parser(json("60", "25", ",\"quietStart\":\"midnight\",\"quietEnd\":\"6am\""), json("60", "25", "")).parse(GROCERY);
        assertThat(prompts.get(1)).contains("must be HH:mm");
    }

    @Test
    void unknownKindBecomesOther() {
        var p = parser(json("60", "25", ",\"kind\":\"pets\"")).parse(GROCERY);
        assertThat(p.kind()).isEqualTo("other");
    }

    @Test
    void twoBadAnswersFallBackToTheRuleBasedReadingAndSaySo() {
        var p = parser("not json", "{\"name\":\"\"}").parse(GROCERY);
        assertThat(p.source()).isEqualTo("heuristic");
        assertThat(p.mandate().periodLimitMinor()).isEqualTo(6_000);
        assertThat(p.notes().getFirst()).contains("AI parser didn't answer");
    }

    @Test
    void unreachableModelFallsBack() {
        var p = parser((LlmTransport) (s, u) -> { throw new LlmException("model endpoint answered 500"); }).parse(GROCERY);
        assertThat(p.source()).isEqualTo("heuristic");
        assertThat(p.mandate().autoApproveUpToMinor()).isEqualTo(2_500);
    }

    @Test
    void noModelUsesTheRuleBasedReadingWithoutComplaint() {
        var p = parser((LlmTransport) null).parse(GROCERY);
        assertThat(p.source()).isEqualTo("heuristic");
        assertThat(p.notes()).noneMatch(n -> n.contains("AI parser"));
    }

    @Test
    void giftCardsAreBlockedByDefaultWithANote() {
        var p = parser((LlmTransport) null).parse("Office supplies, £300 a month, ask me over £50.");
        assertThat(p.mandate().blockedCategories()).contains("gift cards");
        assertThat(p.notes()).anyMatch(n -> n.startsWith("Gift cards are blocked by default"));
    }

    @Test
    void giftCardsStayAllowedWhenTheModelSaysTheTextAllowsThem() {
        var reply = json("200", "50", "").replace("\"allowedCategories\":[\"Groceries\",\"household\"]",
                "\"allowedCategories\":[\"gift cards\"]").replace("\"blockedCategories\":[\"gift cards\"]", "\"blockedCategories\":[]");
        var p = parser(reply).parse("Buy the team's gift cards for Christmas, £200 in total, ask me over £50");
        assertThat(p.mandate().allowedCategories()).containsExactly("gift cards");
        assertThat(p.mandate().blockedCategories()).doesNotContain("gift cards");
    }

    @Test
    void blankOrHugeTextIsRefused() {
        var parser = parser((LlmTransport) null);
        assertThatThrownBy(() -> parser.parse("  ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> parser.parse("x".repeat(MandateParser.MAX_TEXT + 1))).isInstanceOf(IllegalArgumentException.class);
    }

    /** The four example mandates from the brief: the rule-based reading of each passes the same validation as a save. */
    @Test
    void everyExampleProducesAValidMandate() {
        for (String text : List.of(GROCERY,
                "Office supplies for the studio, £300 a month from Viking, Staples or Amazon Business. Ask me over £50. No electronics.",
                "Book the Edinburgh trip: £400 in total, airlines, hotels and rail only, ask me over £100, nothing between midnight and 6am.",
                "Renew our existing software subscriptions, £200 a month, ask me before any new subscription, never annual plans over £100.")) {
            var p = parser((LlmTransport) null).parse(text);
            assertThat(VALIDATOR.validate(p.mandate())).as(text).isEmpty();
        }
    }

    /** Regression: real Gemini 3.5 Flash reply (2026-11-03) sent "notes" as a string, which was rejected twice. */
    @Test
    void geminiStyleReplyWithNotesAsAStringIsAccepted() {
        String gemini = """
                {   "name": "Studio office supplies",   "agentName": "Office bot",   "kind": "office",   "currency": "GBP",
                    "period": "MONTH",   "length": "300.00",   "autoApproveUpTo": "50.00",   "perPurchaseMax": "0.00",
                    "allowedMerchants": [ "Viking", "Staples", "Amazon Business" ], "blockedMerchants": [],
                    "allowedCategories": [ "office supplies" ], "blockedCategories": [ "electronics" ],
                    "maxPurchasesPerHour": 0, "quietStart": null, "quietEnd": null,
                    "notes": "Set category to office supplies based on the mandate title and description." }""";
        var p = parser(gemini).parse("Office supplies for the studio, £300 a month from Viking, Staples or Amazon Business. Ask me over £50. No electronics.");
        assertThat(p.source()).isEqualTo("ai");
        assertThat(prompts).hasSize(1);
        assertThat(p.mandate().periodLimitMinor()).isEqualTo(30_000);
        assertThat(p.mandate().allowedMerchants()).containsExactly("Viking", "Staples", "Amazon Business");
        assertThat(p.notes()).contains("Set category to office supplies based on the mandate title and description.");
    }

    @Test
    void systemPromptSaysListsAreArrays() {
        assertThat(MandateParser.SYSTEM).contains("JSON array of strings, even with one item");
    }
}
