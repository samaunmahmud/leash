package com.leash.risk;

import com.leash.policy.PurchaseRequest;
import com.leash.policy.Reason;

import java.util.List;

/**
 * A second opinion on a purchase. Implementations return extra reasons; Decision.escalate adds them, which can
 * only make the outcome stricter. A risk checker can never approve anything.
 *
 * <p>Planned implementations (see sam_data.md T05/T06): HeuristicRiskChecker (no AI, always on) and LlmRiskChecker.
 *
 * @param pageText text of the page the agent came from, or null
 */
public interface RiskChecker {
    List<Reason> assess(PurchaseRequest request, String pageText);
}
