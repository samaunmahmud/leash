package com.leash.mandate;

import com.leash.policy.Period;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a plain-English mandate without any AI, so the composer still works with no API key. It recognises the usual
 * phrasings ("£60 a week", "ask me over £25", "supermarkets only", "from Viking or Staples", "never gift cards",
 * "nothing between midnight and 6am", "3 purchases an hour") and says what it couldn't read instead of guessing.
 *
 * <p>Money is parsed with BigDecimal into minor units. Never floats.
 */
public final class HeuristicMandateParser {

    private static final String AMT = "[£$€]\\s?((?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d{1,2})?)";
    private static final String TIME = "midnight|noon|midday|\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)|\\d{1,2}:\\d{2}";

    private static final Pattern LENGTH_PER = Pattern.compile(AMT + "\\s*(?:a|an|per|/|each|every)\\s*(day|week|month|trip)\\b");
    private static final Pattern LENGTH_ADJ = Pattern.compile(AMT + "\\s*(daily|weekly|monthly)\\b");
    private static final Pattern LENGTH_TOTAL = Pattern.compile(
            AMT + "\\s*(?:in total|total|altogether|overall|for the (?:whole )?(?:trip|project|event))");
    private static final Pattern SLACK = Pattern.compile(
            "(?:ask|check with|tug|confirm with|ping|tell)\\s+me\\s+(?:for anything |before anything |about anything |if it'?s |when it'?s |if )?"
                    + "(?:over|above|more than|beyond)\\s*" + AMT);
    private static final Pattern SLACK_AUTO = Pattern.compile(
            "(?:auto-?approve|pay without asking|don't ask(?: me)?|no need to ask)\\s+(?:up to|below|under)\\s*" + AMT
                    + "|up to\\s*" + AMT + "\\s*without asking");
    private static final Pattern CAP = Pattern.compile(
            "(?:max(?:imum)?|cap(?:ped)?(?: at)?|no more than|at most)\\s*" + AMT
                    + "\\s*(?:per|a|each|for any)\\s*(?:purchase|order|item|basket|transaction)"
                    + "|no single (?:purchase|order|item) (?:over|above|more than)\\s*" + AMT);
    private static final Pattern VELOCITY = Pattern.compile(
            "(?:max(?:imum)?\\s*|at most\\s*|no more than\\s*|up to\\s*)?(\\d{1,4})\\s*(?:purchases|orders|buys)\\s*(?:an|per|a|each)\\s*hour");
    private static final Pattern QUIET = Pattern.compile(
            "(?:between|from)\\s+(" + TIME + ")\\s+(?:and|to|until|till|-|–)\\s+(" + TIME + ")");
    private static final Pattern ONLY = Pattern.compile("\\bonly\\b");
    private static final Pattern BLOCKED = Pattern.compile(
            "\\b(?:never|no|nothing from|block|don't buy|do not buy|avoid)\\s+([^.;,:]+)");
    /** A capitalised shop name, possibly several words: "Amazon Business", "Sainsbury's". */
    private static final String NAME = "[A-Z][\\w'’&-]*(?:\\s+[A-Z][\\w'’&-]*)*";
    private static final Pattern MERCHANTS = Pattern.compile(
            "\\b(?:from|at)\\s+(" + NAME + "(?:\\s*(?:,|\\bor\\b|\\band\\b)\\s*" + NAME + ")*)");
    private static final Pattern LIST_SPLIT = Pattern.compile("\\s*(?:,|\\band\\b|\\bor\\b|/|&)\\s*");
    private static final Pattern CLAUSE_SPLIT = Pattern.compile("[.;!:,]");

    /** Words people use → the categories Leash's rules and the Larder shop use. */
    private static final Map<String, List<String>> CATEGORY_WORDS = Map.ofEntries(
            Map.entry("supermarket", List.of("groceries", "household")),
            Map.entry("supermarkets", List.of("groceries", "household")),
            Map.entry("grocery", List.of("groceries", "household")),
            Map.entry("groceries", List.of("groceries", "household")),
            Map.entry("food", List.of("groceries")),
            Map.entry("airline", List.of("airlines")),
            Map.entry("airlines", List.of("airlines")),
            Map.entry("flights", List.of("airlines")),
            Map.entry("hotel", List.of("hotels")),
            Map.entry("hotels", List.of("hotels")),
            Map.entry("rail", List.of("rail")),
            Map.entry("trains", List.of("rail")),
            Map.entry("office supplies", List.of("office supplies", "stationery", "furniture")),
            Map.entry("stationery", List.of("stationery")),
            Map.entry("gift card", List.of("gift cards")),
            Map.entry("gift cards", List.of("gift cards")),
            Map.entry("subscriptions", List.of("software")),
            Map.entry("software", List.of("software")));

    private static final Set<String> NOT_CATEGORY_WORDS = Set.of("me", "you", "ask", "buy", "book", "pay", "if", "when");

    public ParsedMandate parse(String text) {
        String src = text.strip();
        String lower = src.toLowerCase(Locale.ROOT);
        var used = new ArrayList<int[]>();
        var notes = new ArrayList<String>();

        // Length + period
        Long length = null;
        Period period = null;
        Matcher m;
        if ((m = LENGTH_PER.matcher(lower)).find()) {
            length = minor(m.group(1));
            period = switch (m.group(2)) {
                case "day" -> Period.DAY;
                case "month" -> Period.MONTH;
                case "trip" -> Period.TOTAL;
                default -> Period.WEEK;
            };
            used.add(span(m));
        } else if ((m = LENGTH_ADJ.matcher(lower)).find()) {
            length = minor(m.group(1));
            period = switch (m.group(2)) {
                case "daily" -> Period.DAY;
                case "monthly" -> Period.MONTH;
                default -> Period.WEEK;
            };
            used.add(span(m));
        } else if ((m = LENGTH_TOTAL.matcher(lower)).find()) {
            length = minor(m.group(1));
            period = Period.TOTAL;
            used.add(span(m));
        }
        if (length == null) notes.add("I couldn't find a spending limit like \"£60 a week\". Set the length.");
        if (period == null) period = Period.WEEK;

        // Slack
        Long slack = null;
        if ((m = SLACK.matcher(lower)).find()) {
            slack = minor(m.group(1));
            used.add(span(m));
        } else if ((m = SLACK_AUTO.matcher(lower)).find()) {
            slack = minor(m.group(1) != null ? m.group(1) : m.group(2));
            used.add(span(m));
        }
        if (slack == null) {
            slack = 0L;
            notes.add("No \"ask me over £…\" amount, so every purchase will ask you first. Set the slack to let small ones through.");
        }
        if (length != null && slack > length) {
            notes.add("The ask-me amount was more than the length, so I lowered it to the length.");
            slack = length;
        }

        // Per-purchase cap
        long cap = 0;
        if ((m = CAP.matcher(lower)).find()) {
            cap = minor(m.group(1) != null ? m.group(1) : m.group(2));
            used.add(span(m));
        }

        // Velocity
        int perHour = 0;
        if ((m = VELOCITY.matcher(lower)).find()) {
            perHour = Integer.parseInt(m.group(1));
            used.add(span(m));
        }

        // Quiet hours
        String quietStart = null, quietEnd = null;
        if ((m = QUIET.matcher(lower)).find()) {
            quietStart = time(m.group(1));
            quietEnd = time(m.group(2));
            if (quietStart != null && quietEnd != null) {
                used.add(span(m));
                notes.add("Purchases between " + quietStart + " and " + quietEnd + " will always ask you (quiet hours).");
            } else {
                quietStart = quietEnd = null;
            }
        }

        // Merchants ("from Viking, Staples or Amazon Business") — read from the original text, names are capitalised
        Set<String> merchants = new LinkedHashSet<>();
        m = MERCHANTS.matcher(src);
        while (m.find()) {
            for (String name : LIST_SPLIT.split(m.group(1))) {
                if (!name.isBlank()) merchants.add(name.strip());
            }
            used.add(span(m));
        }

        // "X, Y and Z only" → allowed categories
        Set<String> categories = new LinkedHashSet<>();
        m = ONLY.matcher(lower);
        while (m.find()) {
            int end = m.start();
            int start = end;
            // Walk back over comma-separated short noun phrases with no amounts in them.
            int i = end;
            while (i > 0) {
                int boundary = lastBoundary(lower, i);
                String seg = lower.substring(boundary == 0 ? 0 : boundary + 1, i).strip();
                if (seg.isEmpty() || !isCategoryPhrase(seg)) break;
                start = boundary == 0 ? 0 : boundary + 1;
                if (boundary == 0 || lower.charAt(boundary) != ',') break;
                i = boundary;
            }
            if (start < end) {
                for (String part : LIST_SPLIT.split(lower.substring(start, end))) addCategory(categories, part);
                used.add(new int[]{start, m.end()});
            }
        }

        // "never gift cards", "no electronics" → blocked categories
        Set<String> blocked = new LinkedHashSet<>();
        m = BLOCKED.matcher(lower);
        while (m.find()) {
            String what = m.group(1).strip();
            if (what.matches(".*[\\d£$€].*") || what.startsWith("more than") || what.startsWith("single")) continue;
            for (String part : LIST_SPLIT.split(what)) addCategory(blocked, part);
            used.add(span(m));
        }

        // Kind, name, agent name
        String kind = kind(lower, categories);
        if (categories.isEmpty()) {
            List<String> topic = topicCategories(kind);
            if (!topic.isEmpty()) {
                categories.addAll(topic);
                notes.add("No shops or categories named, so I limited it to " + String.join(", ", topic) + " from the topic.");
            }
        }
        int firstClauseEnd = firstClauseEnd(src);
        used.add(new int[]{0, firstClauseEnd});
        String name = capitalise(src.substring(0, firstClauseEnd).strip());
        if (name.length() > 60) name = name.substring(0, 60).strip();
        if (name.isBlank()) name = "New leash";

        if (lower.length() == src.length()) notes.addAll(leftovers(src, used));

        var input = new MandateInput(name, currency(lower), period, length == null ? 0 : length, slack, cap,
                merchants, Set.of(), categories, blocked, perHour, quietStart, quietEnd, true, src);
        return new ParsedMandate(input, agentName(kind), kind, "heuristic", notes);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────

    static long minor(String amount) {
        return new BigDecimal(amount.replace(",", "")).movePointRight(2).longValueExact();
    }

    /** "midnight" → 00:00, "6am" → 06:00, "11:30pm" → 23:30, "06:00" → 06:00. Null if it isn't a real time. */
    static String time(String t) {
        String s = t.strip().replace(" ", "");
        if (s.equals("midnight")) return "00:00";
        if (s.equals("noon") || s.equals("midday")) return "12:00";
        Matcher m = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?(am|pm)?").matcher(s);
        if (!m.matches()) return null;
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (m.group(3) != null) {
            if (h < 1 || h > 12) return null;
            h = h % 12 + (m.group(3).equals("pm") ? 12 : 0);
        }
        if (h > 23 || min > 59) return null;
        return String.format(Locale.ROOT, "%02d:%02d", h, min);
    }

    private static int lastBoundary(String s, int before) {
        for (int i = before - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == ',' || c == '.' || c == ';' || c == ':' || c == '!') return i;
        }
        return 0;
    }

    private static boolean isCategoryPhrase(String seg) {
        if (seg.matches(".*[\\d£$€].*")) return false;
        String[] words = seg.split("\\s+");
        if (words.length > 4) return false;
        for (String w : words) if (NOT_CATEGORY_WORDS.contains(w)) return false;
        return true;
    }

    private static void addCategory(Set<String> into, String phrase) {
        String p = phrase.strip().replaceAll("^(the|any|all)\\s+", "");
        if (p.isEmpty()) return;
        List<String> mapped = CATEGORY_WORDS.get(p);
        if (mapped != null) into.addAll(mapped);
        else into.add(p);
    }

    private static String kind(String lower, Set<String> categories) {
        if (categories.contains("groceries") || lower.matches("(?s).*\\b(grocer(y|ies)|supermarkets?|food shop)\\b.*")) return "grocery";
        if (lower.matches("(?s).*\\b(office|stationery)\\b.*")) return "office";
        if (categories.contains("airlines") || categories.contains("hotels") || categories.contains("rail")
                || lower.matches("(?s).*\\b(trip|travel|flights?|holiday)\\b.*")) return "travel";
        return "other";
    }

    private static List<String> topicCategories(String kind) {
        return switch (kind) {
            case "grocery" -> List.of("groceries", "household");
            case "office" -> List.of("office supplies", "stationery", "furniture");
            default -> List.of();
        };
    }

    private static String agentName(String kind) {
        return switch (kind) {
            case "grocery" -> "Grocery bot";
            case "office" -> "Office bot";
            case "travel" -> "Travel bot";
            default -> "Shopping bot";
        };
    }

    private static String currency(String lower) {
        if (lower.contains("$") || lower.contains("usd") || lower.contains("dollar")) return "USD";
        if (lower.contains("€") || lower.contains("eur")) return "EUR";
        return "GBP";
    }

    private static int firstClauseEnd(String s) {
        Matcher m = CLAUSE_SPLIT.matcher(s);
        return m.find() ? m.start() : s.length();
    }

    /** Clauses no rule touched: say so rather than silently drop what the human wrote. */
    private static List<String> leftovers(String src, List<int[]> used) {
        var out = new ArrayList<String>();
        Matcher m = CLAUSE_SPLIT.matcher(src);
        int start = 0;
        while (true) {
            boolean found = m.find();
            int end = found ? m.start() : src.length();
            String clause = src.substring(start, end).strip();
            if (clause.split("\\s+").length >= 2 && !overlaps(start, end, used)) {
                out.add("Not turned into a rule, check it yourself: \"" + clause + "\"");
            }
            if (!found) break;
            start = m.end();
        }
        return out;
    }

    private static boolean overlaps(int start, int end, List<int[]> used) {
        for (int[] u : used) if (u[0] < end && start < u[1]) return true;
        return false;
    }

    private static int[] span(Matcher m) {
        return new int[]{m.start(), m.end()};
    }

    private static String capitalise(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
