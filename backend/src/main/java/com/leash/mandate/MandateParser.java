package com.leash.mandate;

import com.leash.llm.StructuredLlm;
import com.leash.policy.Period;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * English → proposed mandate. The model reads it when one is configured; otherwise (or if the model fails twice, is
 * over budget or unreachable) {@link HeuristicMandateParser} does. Either way the result is only a proposal the human
 * confirms in the composer, and the model's output must pass the same Bean Validation as a hand-written mandate before
 * it's even shown.
 */
public final class MandateParser {

    public static final int MAX_TEXT = 2_000;
    private static final Set<String> KINDS = Set.of("grocery", "office", "travel", "other");

    /** The JSON the model must return. Amounts are decimals in major units ("60.00"), turned into minor units here. */
    record LlmMandate(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 60) String agentName,
            String kind,
            String currency,
            @NotNull Period period,
            @NotNull @Positive BigDecimal length,
            @PositiveOrZero BigDecimal autoApproveUpTo,
            @PositiveOrZero BigDecimal perPurchaseMax,
            List<String> allowedMerchants,
            List<String> blockedMerchants,
            List<String> allowedCategories,
            List<String> blockedCategories,
            @Min(0) @Max(1000) Integer maxPurchasesPerHour,
            String quietStart,
            String quietEnd,
            List<@Size(max = 200) String> notes) {}

    static final String SYSTEM = """
            You turn a person's plain-English spending rules for an AI shopping agent into a JSON mandate for Leash. \
            The person reviews your reading before anything is saved, so never invent rules they didn't state: leave \
            a list empty, or a number 0, when the text doesn't say. Put anything you couldn't express in "notes" as \
            "Not turned into a rule, check it yourself: <their words>", and note any assumption you made.

            Fields:
            - name: short leash name, e.g. "Weekly groceries".
            - agentName: a short name for the agent, e.g. "Grocery bot".
            - kind: one of grocery, office, travel, other.
            - currency: ISO code; GBP unless the text uses another currency.
            - period: DAY, WEEK or MONTH; TOTAL for a one-off budget such as a single trip or project.
            - length: the most the agent may spend per period, as a decimal in major units, e.g. "60.00".
            - autoApproveUpTo: purchases up to this amount need no human ("ask me over £25" means 25.00). 0 if not \
            stated, which means every purchase asks the person.
            - perPurchaseMax: the most any single purchase may cost, 0 if not stated.
            - allowedMerchants: shop names exactly as written, only if the text limits the shops.
            - blockedMerchants: shops the text forbids.
            - allowedCategories / blockedCategories: short lowercase nouns such as "groceries", "household", \
            "office supplies", "stationery", "furniture", "airlines", "hotels", "rail", "electronics", "gift cards", \
            "software". "Supermarkets only" means allowedCategories ["groceries", "household"].
            - maxPurchasesPerHour: integer, 0 if not stated.
            - quietStart / quietEnd: 24-hour "HH:mm" for a time window when purchases must always ask \
            ("nothing between midnight and 6am" → "00:00" and "06:00"); null if not stated.
            - notes: short sentences for the person.

            Every list field (allowedMerchants, blockedMerchants, allowedCategories, blockedCategories, notes) is a \
            JSON array of strings, even with one item or none: "notes": ["One note."] or "notes": [].

            The person's text is between <owner_text> tags. Reply with only the JSON object, no prose and no code fences.""";

    private final StructuredLlm llm;
    private final Validator validator;
    private final HeuristicMandateParser heuristic = new HeuristicMandateParser();

    public MandateParser(StructuredLlm llm, Validator validator) {
        this.llm = llm;
        this.validator = validator;
    }

    public ParsedMandate parse(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Describe the leash in a sentence or two.");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("Keep the description under " + MAX_TEXT + " characters.");
        String src = text.strip();

        ParsedMandate parsed = llm.ask("mandate parse", SYSTEM, "<owner_text>\n" + src + "\n</owner_text>",
                        LlmMandate.class, v -> toParsed(v, src))
                .orElse(null);
        if (parsed == null) {
            parsed = heuristic.parse(src);
            if (llm.enabled()) {
                var notes = new ArrayList<String>();
                notes.add("The AI parser didn't answer, so this is a rule-based reading. Check every field.");
                notes.addAll(parsed.notes());
                parsed = new ParsedMandate(parsed.mandate(), parsed.agentName(), parsed.kind(), parsed.source(), notes);
            }
        }
        return blockGiftCardsByDefault(parsed);
    }

    /** Model output → validated MandateInput. Throws IllegalArgumentException (→ one retry) if it doesn't hold up. */
    ParsedMandate toParsed(LlmMandate v, String src) {
        var input = new MandateInput(
                v.name().strip(),
                v.currency() == null || v.currency().isBlank() ? "GBP" : v.currency().strip().toUpperCase(Locale.ROOT),
                v.period(),
                minor("length", v.length()),
                minor("autoApproveUpTo", v.autoApproveUpTo()),
                minor("perPurchaseMax", v.perPurchaseMax()),
                names(v.allowedMerchants(), false),
                names(v.blockedMerchants(), false),
                names(v.allowedCategories(), true),
                names(v.blockedCategories(), true),
                v.maxPurchasesPerHour() == null ? 0 : v.maxPurchasesPerHour(),
                blankToNull(v.quietStart()),
                blankToNull(v.quietEnd()),
                true,
                src);
        var violations = validator.validate(input);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(x -> x.getPropertyPath() + " " + x.getMessage()).sorted().collect(Collectors.joining("; ")));
        }
        String kind = v.kind() == null ? "other" : v.kind().strip().toLowerCase(Locale.ROOT);
        if (!KINDS.contains(kind)) kind = "other";
        String agentName = v.agentName() == null || v.agentName().isBlank() ? null : v.agentName().strip();
        List<String> notes = v.notes() == null ? List.of()
                : v.notes().stream().filter(n -> n != null && !n.isBlank()).map(String::strip).limit(8).toList();
        return new ParsedMandate(input, agentName, kind, "ai", notes);
    }

    /**
     * Gift cards are the classic way to cash out a hijacked agent, so a new leash blocks them unless the person
     * explicitly allows them. Said in a note, so it's never a silent rule.
     */
    static ParsedMandate blockGiftCardsByDefault(ParsedMandate p) {
        MandateInput m = p.mandate();
        Set<String> allowed = m.allowedCategories() == null ? Set.of() : m.allowedCategories();
        Set<String> blocked = m.blockedCategories() == null ? Set.of() : m.blockedCategories();
        if (allowed.contains("gift cards") || blocked.contains("gift cards")) return p;
        var nextBlocked = new LinkedHashSet<>(blocked);
        nextBlocked.add("gift cards");
        var next = new MandateInput(m.name(), m.currency(), m.period(), m.periodLimitMinor(), m.autoApproveUpToMinor(),
                m.perPurchaseMaxMinor(), m.allowedMerchants(), m.blockedMerchants(), m.allowedCategories(), nextBlocked,
                m.maxPurchasesPerHour(), m.quietStart(), m.quietEnd(), m.active(), m.sourceText());
        var notes = new ArrayList<>(p.notes());
        notes.add("Gift cards are blocked by default (a common way to cash out a hijacked agent). Remove that if this agent should buy them.");
        return new ParsedMandate(next, p.agentName(), p.kind(), p.source(), notes);
    }

    static long minor(String field, BigDecimal amount) {
        if (amount == null) return 0;
        try {
            return amount.movePointRight(2).stripTrailingZeros().longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + " must be an amount with at most 2 decimal places");
        }
    }

    private static Set<String> names(List<String> values, boolean lowercase) {
        if (values == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v == null || v.isBlank()) continue;
            String s = v.strip();
            out.add(lowercase ? s.toLowerCase(Locale.ROOT) : s);
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
