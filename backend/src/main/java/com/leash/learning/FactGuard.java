package com.leash.learning;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Lets a model word a sentence but never choose a number. A model-written sentence is accepted only if every number in
 * it is one the code supplied, it spells out no numbers in words ("five" could be wrong where "5" can be checked), it
 * names every number that must appear (e.g. the suggested slack, so the sentence matches the button), and it's one tidy
 * line. Anything else is rejected and the caller uses its template.
 */
final class FactGuard {

    static final int MAX_CHARS = 320;

    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final Pattern NUMBER_WORDS = Pattern.compile(
            "(?i)\\b(zero|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|"
                    + "sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety|hundred|"
                    + "thousand|million|dozen|half|double|twice|triple|once)\\b");
    private static final Pattern OTHER_CURRENCY = Pattern.compile("[$€¥₹]|\\b(USD|EUR|dollars?|euros?)\\b", Pattern.CASE_INSENSITIVE);

    private FactGuard() {}

    /**
     * @param allowed  every number the sentence may use, as the code computed it ("30.00", "4", ...)
     * @param required numbers that must appear
     */
    static Optional<String> check(String sentence, Collection<String> allowed, Collection<String> required) {
        if (sentence == null) return Optional.empty();
        String s = sentence.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (s.isEmpty() || s.length() > MAX_CHARS) return Optional.empty();
        if (NUMBER_WORDS.matcher(s).find() || OTHER_CURRENCY.matcher(s).find()) return Optional.empty();
        Set<BigDecimal> ok = allowed.stream().map(FactGuard::value).collect(Collectors.toSet());
        Set<BigDecimal> seen = new java.util.HashSet<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            BigDecimal v = value(m.group());
            if (!ok.contains(v)) return Optional.empty();
            seen.add(v);
        }
        for (String r : required) if (!seen.contains(value(r))) return Optional.empty();
        return Optional.of(s);
    }

    /** "30", "30.00" and "1,000" compare by value. */
    static BigDecimal value(String n) {
        return new BigDecimal(n.replace(",", "").replaceAll("\\.$", "")).stripTrailingZeros();
    }

    /** £30.00 → "£30", £18.40 → "£18.40": how a person writes it. */
    static String money(long minor, String currency) {
        String full = com.leash.policy.Money.format(minor, currency);
        return minor % 100 == 0 ? full.replaceAll("\\.00\\b", "") : full;
    }

    static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
