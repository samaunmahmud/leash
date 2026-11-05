package com.leash.learning;

import com.leash.ledger.LedgerRepository;
import com.leash.llm.StructuredLlm;
import com.leash.mandate.MandateInput;
import com.leash.mandate.MandateService;
import com.leash.policy.Mandate;
import com.leash.policy.Reason;
import com.leash.policy.RuleCode;
import com.leash.store.Db;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mandate learning, end to end: reads answered tugs, asks {@link SlackAdvisor} for the number, has the model word one
 * sentence around it (checked by {@link FactGuard}, template otherwise), and applies the raise when the human accepts.
 * An accepted raise is written to {@code leash_changes}, which the ledger shows beside the purchases.
 */
@Service
public class LearningService {

    /** How far back answered tugs count as evidence. */
    static final Duration LOOKBACK = Duration.ofDays(90);
    public static final String SLACK_RAISED = "SLACK_RAISED";

    /** A suggestion as the dashboard shows it. The numbers come from {@link SlackAdvisor}; only {@code sentence} may be AI-worded. */
    public record SuggestionView(String agentId, long fromMinor, long toMinor, String currency, int approvals,
                                 long lowestApprovedMinor, long highestApprovedMinor, String category,
                                 String sentence, boolean byAi) {}

    /** An accepted change, as the ledger shows it. */
    public record LeashChange(String id, String agentId, String kind, long fromMinor, long toMinor, String currency,
                              String summary, String evidence, Instant createdAt) {}

    record Worded(@NotBlank @Size(max = 400) String sentence) {}

    static final String SYSTEM = """
            You write one short, friendly sentence for the owner of an AI shopping agent. Leash has noticed the owner \
            keeps approving purchases just above the agent's auto-approve limit, and suggests raising the limit.

            Rules:
            - Use only the facts given. Do not add facts, advice, dates or percentages.
            - Write every number with digits, exactly as given, with the £ sign for money. Never spell numbers as words.
            - Mention the new limit and end with a question asking whether to raise auto-approve to it.
            - At most 30 words. Plain English, no jargon.

            Reply with only a JSON object: {"sentence": "..."}""";

    private final LedgerRepository ledger;
    private final MandateService leashes;
    private final StructuredLlm llm;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Map<String, Wording> sentences = new ConcurrentHashMap<>();

    private record Wording(String sentence, boolean byAi, Instant at) {}

    public LearningService(LedgerRepository ledger, MandateService leashes, StructuredLlm llm, JdbcClient jdbc, Clock clock) {
        this.ledger = ledger;
        this.leashes = leashes;
        this.llm = llm;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // ── Suggestions ─────────────────────────────────────────────────────────────────────────────────────────

    /** The current suggestion for an agent, unless there's none or the human said "not now" to this exact raise. */
    public Optional<SuggestionView> suggestion(String agentId) {
        MandateService.Leash leash = leashes.get(agentId);
        return advise(leash.mandate())
                .filter(s -> !dismissed(agentId, s.fromMinor(), s.toMinor()))
                .map(s -> view(leash, s));
    }

    Optional<SlackAdvisor.Suggestion> advise(Mandate m) {
        List<SlackAdvisor.AnsweredTug> history = ledger.answeredTugs(m.agentId(), clock.instant().minus(LOOKBACK)).stream()
                .map(t -> new SlackAdvisor.AnsweredTug(t.entry().request().amountMinor(), t.entry().request().currency(),
                        t.entry().request().category(), t.approved(), onlyOverSlack(t.entry().decision().reasons())))
                .toList();
        return SlackAdvisor.suggest(m, history);
    }

    static boolean onlyOverSlack(List<Reason> reasons) {
        return !reasons.isEmpty() && reasons.stream().allMatch(r -> r.code() == RuleCode.OVER_SLACK);
    }

    /**
     * Applies the raise. {@code toMinor} is what the human saw: if the evidence changed since (a new denial, or the
     * slack was edited), the suggestion is recomputed and a mismatch is refused rather than applying a stale number.
     */
    @Transactional
    public LeashChange accept(String agentId, long toMinor) {
        MandateService.Leash leash = leashes.get(agentId);
        SlackAdvisor.Suggestion s = advise(leash.mandate())
                .filter(x -> x.toMinor() == toMinor)
                .orElseThrow(() -> new IllegalStateException(
                        "This suggestion is out of date: the agent's history or leash changed. Reload to see the current one."));
        Mandate m = leash.mandate();
        MandateInput current = MandateInput.from(m, leash.sourceText());
        leashes.updateMandate(agentId, new MandateInput(current.name(), current.currency(), current.period(),
                current.periodLimitMinor(), s.toMinor(), current.perPurchaseMaxMinor(), current.allowedMerchants(),
                current.blockedMerchants(), current.allowedCategories(), current.blockedCategories(),
                current.maxPurchasesPerHour(), current.quietStart(), current.quietEnd(), current.active(), null));
        var change = new LeashChange("chg-" + UUID.randomUUID().toString().substring(0, 12), agentId, SLACK_RAISED,
                s.fromMinor(), s.toMinor(), s.currency(),
                "Auto-approve raised from " + FactGuard.money(s.fromMinor(), s.currency()) + " to "
                        + FactGuard.money(s.toMinor(), s.currency()) + " (you accepted Leash's suggestion).",
                evidence(s), clock.instant());
        jdbc.sql("""
                        INSERT INTO leash_changes (id, agent_id, kind, from_minor, to_minor, currency, summary, evidence, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(change.id(), agentId, change.kind(), change.fromMinor(), change.toMinor(), change.currency(),
                        cut(change.summary(), 500), cut(change.evidence(), 500), Db.ts(change.createdAt()))
                .update();
        return change;
    }

    /** "Not now": this exact raise isn't offered again. New evidence can still lead to a different one. */
    public void dismiss(String agentId, long toMinor) {
        MandateService.Leash leash = leashes.get(agentId);
        long from = leash.mandate().autoApproveUpToMinor();
        if (!dismissed(agentId, from, toMinor)) {
            jdbc.sql("INSERT INTO suggestion_dismissals (agent_id, from_minor, to_minor, created_at) VALUES (?, ?, ?, ?)")
                    .params(agentId, from, toMinor, Db.ts(clock.instant())).update();
        }
    }

    private boolean dismissed(String agentId, long from, long to) {
        return jdbc.sql("SELECT COUNT(*) FROM suggestion_dismissals WHERE agent_id = ? AND from_minor = ? AND to_minor = ?")
                .params(agentId, from, to).query(Integer.class).single() > 0;
    }

    public List<LeashChange> changes(String agentId) {
        String sql = "SELECT * FROM leash_changes " + (agentId == null ? "" : "WHERE agent_id = ? ") + "ORDER BY created_at DESC, id DESC";
        var q = jdbc.sql(sql);
        if (agentId != null) q = q.param(agentId);
        return q.query((rs, i) -> new LeashChange(rs.getString("id"), rs.getString("agent_id"), rs.getString("kind"),
                rs.getLong("from_minor"), rs.getLong("to_minor"), rs.getString("currency"), rs.getString("summary"),
                rs.getString("evidence"), Db.instant(rs, "created_at"))).list();
    }

    // ── Wording ─────────────────────────────────────────────────────────────────────────────────────────────

    private SuggestionView view(MandateService.Leash leash, SlackAdvisor.Suggestion s) {
        Instant now = clock.instant();
        // AI wording is kept; a template (model down or reply rejected) is retried after a while.
        // Not compute(): that would hold a map lock through a model call of up to 12 s.
        String k = key(leash.agent().id(), s);
        Wording w = sentences.get(k);
        if (w == null || (!w.byAi() && !w.at().plus(WalkReportService.RETRY_AI_AFTER).isAfter(now))) {
            w = word(leash.agent().name(), s, now);
            sentences.put(k, w);
        }
        return new SuggestionView(leash.agent().id(), s.fromMinor(), s.toMinor(), s.currency(), s.approvals(),
                s.lowestApprovedMinor(), s.highestApprovedMinor(), s.category(), w.sentence(), w.byAi());
    }

    private Wording word(String agentName, SlackAdvisor.Suggestion s, Instant now) {
        return aiSentence(llm, agentName, s).map(x -> new Wording(x, true, now)).orElseGet(() -> new Wording(template(s), false, now));
    }

    /** The model's wording, if it answered and passed {@link FactGuard}; empty means "use the template". */
    static Optional<String> aiSentence(StructuredLlm llm, String agentName, SlackAdvisor.Suggestion s) {
        List<String> allowed = new ArrayList<>(List.of(String.valueOf(s.approvals())));
        for (long v : new long[]{s.fromMinor(), s.toMinor(), s.lowestApprovedMinor(), s.highestApprovedMinor()}) {
            allowed.add(com.leash.policy.Money.toPayPalValue(v, s.currency()));
        }
        String required = com.leash.policy.Money.toPayPalValue(s.toMinor(), s.currency());
        return llm.ask("slack suggestion", SYSTEM, facts(agentName, s), Worded.class,
                        w -> FactGuard.check(w.sentence(), allowed, List.of(required)))
                .flatMap(x -> x);
    }

    static String facts(String agentName, SlackAdvisor.Suggestion s) {
        return "Agent: " + agentName + "\n"
                + "Purchases the owner approved by hand that were only held for being over the limit: " + s.approvals() + "\n"
                + (s.category() == null ? "" : "Mostly in the category: " + s.category() + "\n")
                + "Lowest of them: " + FactGuard.money(s.lowestApprovedMinor(), s.currency()) + "\n"
                + "Highest of them: " + FactGuard.money(s.highestApprovedMinor(), s.currency()) + "\n"
                + "Purchases in that range the owner turned down: none\n"
                + "Current auto-approve limit: " + FactGuard.money(s.fromMinor(), s.currency()) + "\n"
                + "Suggested new auto-approve limit: " + FactGuard.money(s.toMinor(), s.currency()) + "\n";
    }

    /** The no-AI wording. Same facts, same numbers. */
    static String template(SlackAdvisor.Suggestion s) {
        String what = s.category() == null ? "tugs" : "tugs for " + s.category();
        String range = s.lowestApprovedMinor() == s.highestApprovedMinor()
                ? "at " + FactGuard.money(s.lowestApprovedMinor(), s.currency())
                : "between " + FactGuard.money(s.lowestApprovedMinor(), s.currency()) + " and "
                        + FactGuard.money(s.highestApprovedMinor(), s.currency());
        return "You approved " + s.approvals() + " " + what + " " + range + " and turned none down. Raise auto-approve from "
                + FactGuard.money(s.fromMinor(), s.currency()) + " to " + FactGuard.money(s.toMinor(), s.currency()) + "?";
    }

    static String evidence(SlackAdvisor.Suggestion s) {
        return s.approvals() + " approved tugs from " + FactGuard.money(s.lowestApprovedMinor(), s.currency()) + " to "
                + FactGuard.money(s.highestApprovedMinor(), s.currency()) + " held only for being over "
                + FactGuard.money(s.fromMinor(), s.currency()) + "; no denials up to "
                + FactGuard.money(s.toMinor(), s.currency()) + " in the last " + LOOKBACK.toDays() + " days.";
    }

    private static String key(String agentId, SlackAdvisor.Suggestion s) {
        return String.join("|", agentId, String.valueOf(s.fromMinor()), String.valueOf(s.toMinor()),
                String.valueOf(s.approvals()), String.valueOf(s.lowestApprovedMinor()),
                String.valueOf(s.highestApprovedMinor()), String.valueOf(s.category()));
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
