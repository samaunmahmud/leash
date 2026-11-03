package com.leash.policy;

/**
 * One rule that fired, with a sentence a non-technical person can read.
 *
 * @param evidence optional short quote of what triggered it (e.g. the hidden instruction on a poisoned page), shown in
 *                 the reason card. Untrusted text from a third-party page: always rendered as plain text, never HTML,
 *                 and already cleaned and cut to {@link #EVIDENCE_MAX} characters.
 */
public record Reason(RuleCode code, String message, String evidence) {

    public static final int EVIDENCE_MAX = 220;

    public Reason {
        evidence = clean(evidence);
    }

    public Reason(RuleCode code, String message) {
        this(code, message, null);
    }

    public Outcome forces() {
        return code.forces();
    }

    /** One line, no invisible characters, bounded. */
    static String clean(String s) {
        if (s == null) return null;
        String t = s.replaceAll("[\\u200B-\\u200D\\u2060\\uFEFF]", "").replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (t.isEmpty()) return null;
        return t.length() > EVIDENCE_MAX ? t.substring(0, EVIDENCE_MAX - 1).strip() + "…" : t;
    }
}
