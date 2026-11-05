package com.leash.learning;

import com.leash.config.PolicyConfig;
import com.leash.ledger.EntryStatus;
import com.leash.ledger.LedgerEntry;
import com.leash.ledger.LedgerRepository;
import com.leash.ledger.LedgerService;
import com.leash.llm.StructuredLlm;
import com.leash.mandate.MandateService;
import com.leash.policy.Mandate;
import com.leash.policy.Money;
import com.leash.policy.Outcome;
import com.leash.policy.Period;
import com.leash.policy.Periods;
import com.leash.policy.Reason;
import com.leash.policy.RuleCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The walk report: what one agent did this week (Monday 00:00 UK time to now). Every figure is computed here from the
 * ledger; the model only words a two-sentence summary of those figures, checked by {@link FactGuard}, and it never sees
 * item names, pages or evidence (third-party text that could carry an injection).
 */
@Service
public class WalkReportService {

    static final int NOTABLE_BLOCKS = 3;

    /** Which block reasons matter most, most important first. Anything not listed ranks after. */
    static final List<RuleCode> BLOCK_RANK = List.of(RuleCode.PROMPT_INJECTION, RuleCode.LOOKALIKE_MERCHANT,
            RuleCode.AI_RISK_HIGH, RuleCode.AGENT_REVOKED, RuleCode.CATEGORY_BLOCKED, RuleCode.MERCHANT_BLOCKED,
            RuleCode.CATEGORY_NOT_ALLOWED, RuleCode.MERCHANT_NOT_ALLOWED, RuleCode.OVER_PURCHASE_MAX,
            RuleCode.OVER_LENGTH, RuleCode.VELOCITY_LIMIT);

    /** Code-owned words for each block reason: the only description of a block the model gets. */
    static final Map<RuleCode, String> BLOCK_WORDS = Map.ofEntries(
            Map.entry(RuleCode.PROMPT_INJECTION, "a shop page tried to give the agent hidden instructions"),
            Map.entry(RuleCode.LOOKALIKE_MERCHANT, "a shop name imitated a trusted shop"),
            Map.entry(RuleCode.AI_RISK_HIGH, "the AI risk check rated it high risk"),
            Map.entry(RuleCode.AGENT_REVOKED, "the agent was snapped back"),
            Map.entry(RuleCode.CATEGORY_BLOCKED, "a category the owner forbade"),
            Map.entry(RuleCode.MERCHANT_BLOCKED, "a shop the owner forbade"),
            Map.entry(RuleCode.CATEGORY_NOT_ALLOWED, "a category outside the leash"),
            Map.entry(RuleCode.MERCHANT_NOT_ALLOWED, "a shop outside the leash"),
            Map.entry(RuleCode.OVER_PURCHASE_MAX, "over the per-purchase cap"),
            Map.entry(RuleCode.OVER_LENGTH, "over the length"),
            Map.entry(RuleCode.VELOCITY_LIMIT, "too many purchases in an hour"));

    public record NotableBlock(String entryId, Instant at, String description, String merchant, long amountMinor,
                               String currency, String code, String reason, String evidence) {}

    public record WalkReport(String agentId, String agentName, String agentKind, boolean revoked,
                             Instant from, Instant to, Period period, String currency,
                             long lengthMinor, long usedMinor, long pendingMinor,
                             int requests, int approved, int paid, int tugged, int blocked, int refunded, long paidMinor,
                             List<NotableBlock> notableBlocks,
                             LearningService.SuggestionView suggestion,
                             List<LearningService.LeashChange> changes,
                             String summary, boolean summaryByAi) {}

    record Worded(@NotBlank @Size(max = 600) String summary) {}

    static final String SYSTEM = """
            You write the weekly "walk report" for the owner of an AI shopping agent: a summary of what the agent \
            did this week under its spending rules.

            Rules:
            - Exactly two short sentences, at most 45 words in total. Plain, calm English; no jargon, no emoji.
            - Use only the facts given. Don't invent purchases, reasons, advice or trends.
            - Write every number with digits, exactly as given, with the £ sign for money. Never spell numbers as words.
            - Leash makes every decision, not the agent: write "Leash blocked", never "the agent blocked".
            - Mention approved, held and blocked requests before payment details.
            - If something was blocked, say what kind of thing in a few words.

            Reply with only a JSON object: {"summary": "..."}""";

    private final MandateService leashes;
    private final LedgerRepository repo;
    private final LedgerService ledger;
    private final LearningService learning;
    private final StructuredLlm llm;
    private final Clock clock;
    /** AI wording is kept until the facts change; a template (model down or reply rejected) is retried after a while. */
    static final java.time.Duration RETRY_AI_AFTER = java.time.Duration.ofMinutes(10);
    private final Map<String, Cached> summaries = new ConcurrentHashMap<>();

    private record Cached(String text, boolean byAi, Instant at) {}

    public WalkReportService(MandateService leashes, LedgerRepository repo, LedgerService ledger,
                             LearningService learning, StructuredLlm llm, Clock clock) {
        this.leashes = leashes;
        this.repo = repo;
        this.ledger = ledger;
        this.learning = learning;
        this.llm = llm;
        this.clock = clock;
    }

    public List<WalkReport> all() {
        return leashes.list().stream().map(this::report).toList();
    }

    public WalkReport report(String agentId) {
        return report(leashes.get(agentId));
    }

    WalkReport report(MandateService.Leash leash) {
        Mandate m = leash.mandate();
        Instant now = clock.instant();
        Instant from = Periods.start(Period.WEEK, now, PolicyConfig.ZONE);
        List<LedgerEntry> week = repo.since(m.agentId(), from);
        LedgerService.Usage usage = ledger.usage(m);

        int approved = 0, paid = 0, tugged = 0, blocked = 0, refunded = 0;
        long paidMinor = 0;
        for (LedgerEntry e : week) {
            Outcome o = e.decision().outcome();
            if (o == Outcome.APPROVE) approved++;
            if (o == Outcome.TUG) tugged++;
            if (o == Outcome.BLOCK) blocked++;
            if (e.status() == EntryStatus.PAID) {
                paid++;
                if (e.request().currency().equals(m.currency())) paidMinor += e.request().amountMinor();
            }
            if (e.status() == EntryStatus.REFUNDED) refunded++;
        }

        List<NotableBlock> notable = week.stream()
                .filter(e -> e.decision().outcome() == Outcome.BLOCK)
                .sorted(Comparator.comparingInt((LedgerEntry e) -> rank(lead(e.decision().reasons()).code()))
                        .thenComparing(e -> e.request().requestedAt(), Comparator.reverseOrder()))
                .limit(NOTABLE_BLOCKS)
                .map(e -> {
                    Reason r = lead(e.decision().reasons());
                    return new NotableBlock(e.id(), e.request().requestedAt(), e.request().description(),
                            e.request().merchant(), e.request().amountMinor(), e.request().currency(), r.code().name(),
                            r.message(), r.evidence());
                })
                .toList();

        var suggestion = learning.suggestion(m.agentId()).orElse(null);
        var changes = learning.changes(m.agentId()).stream().filter(c -> !c.createdAt().isBefore(from)).toList();
        var facts = new Facts(leash.agent().name(), m, usage, week.size(), approved, paid, tugged, blocked, refunded, paidMinor, notable, suggestion);
        // Not compute(): that would hold a map lock through a model call of up to 12 s.
        Cached summary = summaries.get(facts.key());
        if (summary == null || (!summary.byAi() && !summary.at().plus(RETRY_AI_AFTER).isAfter(now))) {
            summary = summarise(facts, now);
            summaries.put(facts.key(), summary);
        }
        return new WalkReport(m.agentId(), leash.agent().name(), leash.agent().kind(), leash.agent().revoked(),
                from, now, m.period(), m.currency(), m.periodLimitMinor(), usage.spentMinor(), usage.pendingMinor(),
                week.size(), approved, paid, tugged, blocked, refunded, paidMinor, notable, suggestion, changes,
                summary.text(), summary.byAi());
    }

    /** The reason that best explains a block: the highest-ranked one that forces BLOCK, else the first. */
    static Reason lead(List<Reason> reasons) {
        return reasons.stream().filter(r -> r.forces() == Outcome.BLOCK).min(Comparator.comparingInt(r -> rank(r.code())))
                .orElse(reasons.isEmpty() ? new Reason(RuleCode.OVER_LENGTH, "Blocked") : reasons.getFirst());
    }

    static int rank(RuleCode c) {
        int i = BLOCK_RANK.indexOf(c);
        return i < 0 ? BLOCK_RANK.size() : i;
    }

    // ── Summary ─────────────────────────────────────────────────────────────────────────────────────────────

    record Facts(String agentName, Mandate m, LedgerService.Usage usage, int requests, int approved, int paid, int tugged, int blocked,
                 int refunded, long paidMinor, List<NotableBlock> notable, LearningService.SuggestionView suggestion) {

        String money(long minor) {
            return FactGuard.money(minor, m.currency());
        }

        List<String> numbers() {
            var n = new ArrayList<String>();
            for (int i : new int[]{requests, approved, paid, tugged, blocked, refunded}) n.add(String.valueOf(i));
            for (long v : new long[]{m.periodLimitMinor(), usage.spentMinor(), usage.pendingMinor(), paidMinor}) {
                n.add(Money.toPayPalValue(v, m.currency()));
            }
            for (NotableBlock b : notable) n.add(Money.toPayPalValue(b.amountMinor(), m.currency()));
            if (suggestion != null) {
                n.add(Money.toPayPalValue(suggestion.fromMinor(), m.currency()));
                n.add(Money.toPayPalValue(suggestion.toMinor(), m.currency()));
            }
            return n;
        }

        String prompt() {
            var b = new StringBuilder();
            b.append("Agent: ").append(agentName).append('\n');
            b.append("Requests this week: ").append(requests).append('\n');
            b.append("Approved by Leash without asking the owner: ").append(approved).append('\n');
            b.append("Payments completed in PayPal so far: ").append(paid).append(" (").append(money(paidMinor)).append(")\n");
            b.append("Held by Leash for the owner to approve (tugs): ").append(tugged).append('\n');
            b.append("Blocked by Leash: ").append(blocked).append('\n');
            if (refunded > 0) b.append("Refunded by the owner: ").append(refunded).append('\n');
            b.append("Length (spending limit) per ").append(m.period().name().toLowerCase(java.util.Locale.ROOT))
                    .append(": ").append(money(m.periodLimitMinor())).append("; used so far: ").append(money(usage.spentMinor()));
            if (usage.pendingMinor() > 0) b.append("; waiting for approval: ").append(money(usage.pendingMinor()));
            b.append('\n');
            for (NotableBlock nb : notable) {
                b.append("Blocked purchase: ").append(money(nb.amountMinor())).append(", because ")
                        .append(BLOCK_WORDS.getOrDefault(RuleCode.valueOf(nb.code()), "it broke a leash rule")).append('\n');
            }
            if (suggestion != null) {
                b.append("Leash suggests raising auto-approve from ").append(money(suggestion.fromMinor())).append(" to ")
                        .append(money(suggestion.toMinor())).append('\n');
            }
            return b.toString();
        }

        String key() {
            return prompt();
        }
    }

    private Cached summarise(Facts f, Instant now) {
        return aiSummary(llm, f).map(s -> new Cached(s, true, now)).orElseGet(() -> new Cached(template(f), false, now));
    }

    /** The model's summary, if there was anything to summarise and it passed {@link FactGuard}. */
    static java.util.Optional<String> aiSummary(StructuredLlm llm, Facts f) {
        if (f.requests() == 0) return java.util.Optional.empty();
        return llm.ask("walk report", SYSTEM, f.prompt(), Worded.class,
                        w -> FactGuard.check(w.summary(), f.numbers(), List.of()))
                .flatMap(x -> x);
    }

    /** The no-AI summary: same facts, same numbers. */
    static String template(Facts f) {
        if (f.requests() == 0) return f.agentName() + " didn't ask to buy anything this week.";
        String first = f.agentName() + " made " + plural(f.requests(), "request") + " this week: " + f.approved() + " approved, "
                + f.tugged() + " sent to you, " + f.blocked() + " blocked.";
        String second = "It has used " + f.money(f.usage().spentMinor()) + " of its " + f.money(f.m().periodLimitMinor())
                + " " + periodWord(f.m().period()) + " length";
        if (!f.notable().isEmpty()) {
            second += ", and the block to know about: "
                    + BLOCK_WORDS.getOrDefault(RuleCode.valueOf(f.notable().getFirst().code()), "it broke a leash rule");
        }
        return first + " " + second + ".";
    }

    static String periodWord(Period p) {
        return switch (p) {
            case DAY -> "daily";
            case WEEK -> "weekly";
            case MONTH -> "monthly";
            case TOTAL -> "total";
        };
    }

    static String plural(int n, String word) {
        return n + " " + word + (n == 1 ? "" : "s");
    }
}
