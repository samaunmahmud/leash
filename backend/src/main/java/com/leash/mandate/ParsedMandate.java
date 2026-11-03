package com.leash.mandate;

import java.util.List;

/**
 * A proposed leash read from plain English. Only a proposal: the human checks it in the composer and nothing is saved
 * until they confirm, when it goes through the same validation as any other {@link MandateInput}.
 *
 * @param source "ai" (read by the model) or "heuristic" (rule-based reading, no model or the model failed)
 * @param notes  things the human should check: assumptions made, parts of the text that couldn't become a rule
 */
public record ParsedMandate(MandateInput mandate, String agentName, String kind, String source, List<String> notes) {

    public ParsedMandate {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
