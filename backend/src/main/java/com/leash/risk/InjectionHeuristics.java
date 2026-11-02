package com.leash.risk;

import com.leash.policy.PurchaseRequest;
import com.leash.policy.Reason;
import com.leash.policy.RuleCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Starter version of the heuristic risk checker: catches the obvious prompt-injection phrases with no AI at all.
 * T05 grows this into the full HeuristicRiskChecker (look-alike merchants, price spikes, duplicates).
 */
public final class InjectionHeuristics implements RiskChecker {

    private static final List<Pattern> PHRASES = List.of(
            Pattern.compile("ignore (all |any |your )?(previous|prior|earlier) (instructions|rules)"),
            Pattern.compile("disregard (your|the) (budget|limits?|rules|instructions)"),
            Pattern.compile("you are now"),
            Pattern.compile("system prompt"),
            Pattern.compile("(as an ai|ai agent|assistant)[,:]? (you must|please) (buy|purchase|order)"),
            Pattern.compile("do not (tell|inform|ask) (the )?(user|human|owner)"));

    @Override
    public List<Reason> assess(PurchaseRequest request, String pageText) {
        List<Reason> out = new ArrayList<>();
        String haystack = String.join(" \n ",
                nz(pageText), nz(request.description()), nz(request.reason())).toLowerCase(Locale.ROOT);
        for (Pattern p : PHRASES) {
            if (p.matcher(haystack).find()) {
                out.add(new Reason(RuleCode.PROMPT_INJECTION,
                        "The page or request contains text that tries to give the agent new instructions."));
                break;
            }
        }
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
