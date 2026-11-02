package com.leash.risk;

import com.leash.policy.PurchaseRequest;
import com.leash.policy.Reason;
import com.leash.policy.RuleCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Risk checks with no AI at all, so Leash still catches the classic attacks with no API key and no network:
 * <ul>
 *   <li>prompt injection: instruction phrases, and instructions hidden in the page (display:none, zero-width text...)</li>
 *   <li>look-alike merchants: "Tesc0" next to an allowlisted "Tesco"</li>
 *   <li>price spikes: more than 3× this agent's median for the category</li>
 *   <li>duplicates: same merchant, amount and item within 10 minutes</li>
 *   <li>task mismatch: the task is groceries but the item is gift cards</li>
 * </ul>
 * Escalate-only like every risk checker: it returns reasons, never a verdict.
 */
public final class HeuristicRiskChecker implements RiskChecker {

    // ── Prompt injection ────────────────────────────────────────────────────────────────────────────────────

    private static final List<Pattern> INSTRUCTION_PHRASES = List.of(
            Pattern.compile("ignore (all |any |your |the )?(previous|prior|earlier|above) (instructions|rules|prompts?)"),
            Pattern.compile("disregard (your|the|all) (budget|limits?|rules|instructions|mandate)"),
            Pattern.compile("forget (your|the|all) (rules|instructions|limits?)"),
            Pattern.compile("you are now"),
            Pattern.compile("new instructions?:"),
            Pattern.compile("system prompt"),
            Pattern.compile("(the )?(user|owner|human) (has )?(already )?(pre-?approved|authori[sz]ed) (this|the purchase)"),
            Pattern.compile("(as an ai|ai agent|assistant|purchasing agent)[,:]? (you must|please|immediately) (buy|purchase|order)"),
            Pattern.compile("do not (tell|inform|ask|notify) (the )?(user|human|owner)"),
            Pattern.compile("(bypass|override|raise) (the |your )?(spending )?(limit|leash|budget|approval)"));

    /** Text a human visitor can't see but an agent reading the HTML can. */
    private static final List<Pattern> HIDDEN = List.of(
            Pattern.compile("<[^>]+style\\s*=\\s*\"[^\"]*(display\\s*:\\s*none|visibility\\s*:\\s*hidden|font-size\\s*:\\s*0|opacity\\s*:\\s*0)[^\"]*\"[^>]*>(.*?)</", Pattern.DOTALL),
            Pattern.compile("<!--(.*?)-->", Pattern.DOTALL),
            Pattern.compile("<[^>]+aria-hidden\\s*=\\s*\"true\"[^>]*>(.*?)</", Pattern.DOTALL));
    private static final Pattern ZERO_WIDTH = Pattern.compile("[\\u200B-\\u200D\\u2060\\uFEFF]");
    private static final Pattern IMPERATIVE = Pattern.compile("\\b(buy|purchase|order|checkout|pay|transfer|approve)\\b");

    // ── Merchants ───────────────────────────────────────────────────────────────────────────────────────────

    /** Well-known merchants a look-alike might imitate even if this leash has never used them. */
    private static final Set<String> KNOWN_MERCHANTS = Set.of(
            "larder", "tesco", "sainsburys", "asda", "waitrose", "ocado", "morrisons", "aldi", "lidl",
            "amazon", "amazonbusiness", "viking", "staples", "trainline", "premierinn", "easyjet", "ryanair",
            "britishairways", "paypal", "apple", "google", "steam");

    private static final Map<Character, Character> CONFUSABLES = Map.of(
            '0', 'o', '1', 'l', '3', 'e', '4', 'a', '5', 's', '7', 't', '8', 'b', '$', 's', '@', 'a', '|', 'l');

    // ── Task vs category ────────────────────────────────────────────────────────────────────────────────────

    private record Domain(Pattern task, Set<String> categories) {}

    private static final List<Domain> DOMAINS = List.of(
            new Domain(Pattern.compile("\\b(grocer(y|ies)|food|supermarket|meals?|larder|shopping list)\\b"),
                    Set.of("groceries", "household", "food", "drinks")),
            new Domain(Pattern.compile("\\b(office|stationery|printer|desk|studio supplies)\\b"),
                    Set.of("office supplies", "stationery", "furniture", "printing")),
            new Domain(Pattern.compile("\\b(trip|travel|flights?|hotels?|trains?|holiday)\\b"),
                    Set.of("travel", "airlines", "hotels", "rail", "transport")),
            new Domain(Pattern.compile("\\b(birthday|gifts?|presents?)\\b"),
                    Set.of("gifts", "toys", "books", "flowers", "homeware")),
            new Domain(Pattern.compile("\\b(datasets?|api credits?|research data|compute)\\b"),
                    Set.of("data", "api credits", "software", "cloud")));

    /** Categories that are a classic way to cash out a hijacked agent: always questioned unless the task names them. */
    private static final Set<String> CASH_LIKE = Set.of("gift cards", "gift card", "crypto", "cryptocurrency", "vouchers",
            "prepaid cards", "money transfer");

    static final Duration DUPLICATE_WINDOW = Duration.ofMinutes(10);
    static final int SPIKE_MULTIPLE = 3;
    static final int SPIKE_MIN_HISTORY = 3;

    @Override
    public List<Reason> assess(RiskContext ctx) {
        List<Reason> out = new ArrayList<>();
        PurchaseRequest r = ctx.request();
        injection(ctx).ifPresent(out::add);
        lookalike(ctx).ifPresent(out::add);
        priceSpike(ctx).ifPresent(out::add);
        duplicate(ctx).ifPresent(out::add);
        taskMismatch(r).ifPresent(out::add);
        return out;
    }

    // ── Signals ─────────────────────────────────────────────────────────────────────────────────────────────

    java.util.Optional<Reason> injection(RiskContext ctx) {
        PurchaseRequest r = ctx.request();
        String page = nz(ctx.pageText());
        String lowerAll = String.join(" \n ", page, nz(r.description()), nz(r.reason()), nz(r.merchant()))
                .toLowerCase(Locale.ROOT);
        String plain = ZERO_WIDTH.matcher(lowerAll).replaceAll("");

        // Hidden text that tells the agent to do something is the strongest signal.
        String lowerPage = page.toLowerCase(Locale.ROOT);
        for (Pattern p : HIDDEN) {
            Matcher m = p.matcher(lowerPage);
            while (m.find()) {
                String hidden = m.group(m.groupCount());
                if (hidden != null && (IMPERATIVE.matcher(hidden).find() || anyPhrase(hidden))) {
                    return java.util.Optional.of(new Reason(RuleCode.PROMPT_INJECTION,
                            "The page hides text from people that tells the agent to buy something. That's a prompt-injection attack."));
                }
            }
        }
        if (ZERO_WIDTH.matcher(lowerAll).find() && (anyPhrase(plain) || IMPERATIVE.matcher(plain).find())) {
            return java.util.Optional.of(new Reason(RuleCode.PROMPT_INJECTION,
                    "The page uses invisible characters around instructions to the agent."));
        }
        if (anyPhrase(plain)) {
            return java.util.Optional.of(new Reason(RuleCode.PROMPT_INJECTION,
                    "The page or request contains text that tries to give the agent new instructions."));
        }
        return java.util.Optional.empty();
    }

    java.util.Optional<Reason> lookalike(RiskContext ctx) {
        String merchant = ctx.request().merchant();
        String canon = canon(merchant);
        if (canon.length() < 4) return java.util.Optional.empty();

        Set<String> trusted = new LinkedHashSet<>(); // allowlisted + used before: distance check applies
        if (ctx.mandate() != null) ctx.mandate().allowedMerchants().forEach(m -> trusted.add(canon(m)));
        ctx.history().forEach(p -> trusted.add(canon(p.merchant())));
        Set<String> all = new HashSet<>(trusted);
        all.addAll(KNOWN_MERCHANTS);

        if (all.contains(canon)) return java.util.Optional.empty(); // it IS a known merchant
        String skeleton = skeleton(merchant);
        for (String known : all) {
            if (skeleton.equals(skeleton(known))) return java.util.Optional.of(lookalikeReason(merchant, known));
        }
        for (String known : trusted) {
            int d = levenshtein(canon, known);
            int allowed = Math.min(canon.length(), known.length()) >= 8 ? 2 : 1;
            if (d > 0 && d <= allowed) return java.util.Optional.of(lookalikeReason(merchant, known));
        }
        return java.util.Optional.empty();
    }

    java.util.Optional<Reason> priceSpike(RiskContext ctx) {
        PurchaseRequest r = ctx.request();
        List<Long> same = ctx.history().stream()
                .filter(p -> p.category().equalsIgnoreCase(r.category()))
                .map(RiskContext.Past::amountMinor).sorted().toList();
        if (same.size() < SPIKE_MIN_HISTORY) return java.util.Optional.empty();
        long median = same.size() % 2 == 1
                ? same.get(same.size() / 2)
                : (same.get(same.size() / 2 - 1) + same.get(same.size() / 2)) / 2;
        if (median > 0 && r.amountMinor() > median * SPIKE_MULTIPLE) {
            long times = r.amountMinor() / median;
            return java.util.Optional.of(new Reason(RuleCode.PRICE_SPIKE,
                    "That's about " + times + "× what this agent usually spends on " + r.category() + "."));
        }
        return java.util.Optional.empty();
    }

    java.util.Optional<Reason> duplicate(RiskContext ctx) {
        PurchaseRequest r = ctx.request();
        if (r.requestedAt() == null) return java.util.Optional.empty();
        String canon = canon(r.merchant());
        String desc = nz(r.description()).trim().toLowerCase(Locale.ROOT);
        boolean dup = ctx.history().stream().anyMatch(p ->
                p.amountMinor() == r.amountMinor()
                        && canon(p.merchant()).equals(canon)
                        && nz(p.description()).trim().toLowerCase(Locale.ROOT).equals(desc)
                        && p.requestedAt() != null
                        && !p.requestedAt().isAfter(r.requestedAt())
                        && Duration.between(p.requestedAt(), r.requestedAt()).compareTo(DUPLICATE_WINDOW) <= 0);
        return dup
                ? java.util.Optional.of(new Reason(RuleCode.DUPLICATE_REQUEST,
                "The same order was requested less than 10 minutes ago. Is this a duplicate?"))
                : java.util.Optional.empty();
    }

    java.util.Optional<Reason> taskMismatch(PurchaseRequest r) {
        String task = nz(r.task()).toLowerCase(Locale.ROOT);
        String category = nz(r.category()).trim().toLowerCase(Locale.ROOT);
        if (task.isBlank() || category.isBlank() || task.contains(category)) return java.util.Optional.empty();
        if (CASH_LIKE.contains(category)) {
            return java.util.Optional.of(new Reason(RuleCode.TASK_MISMATCH,
                    "The task doesn't mention " + category + ", which are a common way to cash out a hijacked agent."));
        }
        for (Domain d : DOMAINS) {
            if (d.task().matcher(task).find()) {
                if (d.categories().contains(category)) return java.util.Optional.empty();
                return java.util.Optional.of(new Reason(RuleCode.TASK_MISMATCH,
                        "The task is \"" + r.task() + "\" but this is " + category + "."));
            }
        }
        return java.util.Optional.empty();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    private static Reason lookalikeReason(String merchant, String known) {
        return new Reason(RuleCode.LOOKALIKE_MERCHANT,
                "\"" + merchant + "\" looks like an imitation of " + known + ". Leash won't pay a look-alike shop.");
    }

    private static boolean anyPhrase(String lower) {
        for (Pattern p : INSTRUCTION_PHRASES) if (p.matcher(lower).find()) return true;
        return false;
    }

    /** Lower-case letters and digits only: "Sainsbury's" and "sainsburys" are the same shop. */
    static String canon(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Canon with look-alike characters folded: "Tesc0" and "Tesco" share a skeleton. */
    static String skeleton(String s) {
        String lower = s == null ? "" : s.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder();
        for (char c : lower.toCharArray()) {
            Character mapped = CONFUSABLES.get(c);
            char x = mapped != null ? mapped : c;
            if ((x >= 'a' && x <= 'z')) b.append(x);
            else if (x >= '0' && x <= '9') b.append(x);
        }
        return b.toString().replace("rn", "m").replace("vv", "w");
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
