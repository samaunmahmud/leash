import type { LedgerEntry, Outcome, Reason } from "../types";
import { riskLevel, type RiskLevel } from "./ledgerView";

/** What each rule code forces. Mirrors com.leash.policy.RuleCode. */
export const FORCES: Record<string, Outcome> = {
  AGENT_REVOKED: "BLOCK", MANDATE_INACTIVE: "BLOCK", WRONG_MANDATE: "BLOCK", CURRENCY_MISMATCH: "BLOCK",
  INVALID_AMOUNT: "BLOCK", MERCHANT_BLOCKED: "BLOCK", MERCHANT_NOT_ALLOWED: "BLOCK", CATEGORY_BLOCKED: "BLOCK",
  CATEGORY_NOT_ALLOWED: "BLOCK", OVER_PURCHASE_MAX: "BLOCK", OVER_LENGTH: "BLOCK", VELOCITY_LIMIT: "BLOCK",
  OVER_SLACK: "TUG", QUIET_HOURS: "TUG",
  PROMPT_INJECTION: "BLOCK", LOOKALIKE_MERCHANT: "BLOCK", TASK_MISMATCH: "TUG", PRICE_SPIKE: "TUG",
  DUPLICATE_REQUEST: "TUG", AI_RISK_HIGH: "BLOCK", AI_RISK_MEDIUM: "TUG",
};

const RISK = ["PROMPT_INJECTION", "LOOKALIKE_MERCHANT", "TASK_MISMATCH", "PRICE_SPIKE", "DUPLICATE_REQUEST"];
const AI = ["AI_RISK_HIGH", "AI_RISK_MEDIUM"];

export type Lane = "rules" | "risk" | "ai";
/** Which check raised a reason: the deterministic engine, the rule-based risk checks, or the AI. */
export function laneOf(code: string): Lane {
  if (AI.includes(code)) return "ai";
  if (RISK.includes(code)) return "risk";
  return "rules";
}

const SEVERITY: Record<Outcome, number> = { APPROVE: 0, TUG: 1, BLOCK: 2 };
const worst = (a: Outcome, b: Outcome): Outcome => (SEVERITY[b] > SEVERITY[a] ? b : a);
const verdict = (rs: Reason[]): Outcome => rs.reduce<Outcome>((o, r) => worst(o, FORCES[r.code] ?? "APPROVE"), "APPROVE");

export interface Chain {
  /** The engine's own verdict from the leash rules. */
  rules: Outcome;
  /** The rule-based risk checks' verdict, or null when they found nothing. */
  risk: Outcome | null;
  /** The AI risk check: whether it gave an answer, and its level. */
  ai: { ran: boolean; level: RiskLevel; outcome: Outcome | null };
  final: Outcome;
}

/**
 * Each check's own say, and the final decision. Final is always the strictest of them: that's the guarantee the card
 * makes visible (rules: tug, AI: high, final: blocked).
 */
export function decisionChain(e: LedgerEntry): Chain {
  const rs = e.decision.reasons;
  const ruleReasons = rs.filter((r) => laneOf(r.code) === "rules");
  const riskReasons = rs.filter((r) => laneOf(r.code) === "risk");
  const aiReasons = rs.filter((r) => laneOf(r.code) === "ai");
  const aiRan = aiReasons.length > 0 || !!e.riskExplanation;
  const aiLevel: RiskLevel = aiReasons.some((r) => r.code === "AI_RISK_HIGH") ? "HIGH"
    : aiReasons.some((r) => r.code === "AI_RISK_MEDIUM") ? "MEDIUM" : aiRan ? "LOW" : "NONE";
  return {
    rules: verdict(ruleReasons),
    risk: riskReasons.length ? verdict(riskReasons) : null,
    ai: { ran: aiRan, level: aiLevel, outcome: aiRan ? verdict(aiReasons) : null },
    final: e.decision.outcome,
  };
}

/** Most important first: an attack beats a rule, a block beats a tug. Injection always leads. */
const RANK = ["PROMPT_INJECTION", "LOOKALIKE_MERCHANT", "AI_RISK_HIGH", "AGENT_REVOKED", "MANDATE_INACTIVE",
  "MERCHANT_BLOCKED", "CATEGORY_BLOCKED", "MERCHANT_NOT_ALLOWED", "CATEGORY_NOT_ALLOWED", "OVER_PURCHASE_MAX",
  "OVER_LENGTH", "VELOCITY_LIMIT", "WRONG_MANDATE", "CURRENCY_MISMATCH", "INVALID_AMOUNT", "TASK_MISMATCH",
  "AI_RISK_MEDIUM", "PRICE_SPIKE", "DUPLICATE_REQUEST", "OVER_SLACK", "QUIET_HOURS"];
export function rankReasons(rs: Reason[]): Reason[] {
  const rank = (c: string) => (RANK.indexOf(c) < 0 ? RANK.length : RANK.indexOf(c));
  return [...rs].sort((a, b) => SEVERITY[FORCES[b.code] ?? "APPROVE"] - SEVERITY[FORCES[a.code] ?? "APPROVE"] || rank(a.code) - rank(b.code));
}

/** Short plain-English titles for the card. */
export const REASON_TITLE: Record<string, string> = {
  PROMPT_INJECTION: "Prompt injection", LOOKALIKE_MERCHANT: "Look-alike shop", AI_RISK_HIGH: "AI: high risk",
  AI_RISK_MEDIUM: "AI: worth a look", AGENT_REVOKED: "Agent snapped back", MANDATE_INACTIVE: "Leash paused",
  WRONG_MANDATE: "Wrong leash", CURRENCY_MISMATCH: "Wrong currency", INVALID_AMOUNT: "Invalid amount",
  MERCHANT_BLOCKED: "Shop blocked", MERCHANT_NOT_ALLOWED: "Shop not on the list", CATEGORY_BLOCKED: "Category blocked",
  CATEGORY_NOT_ALLOWED: "Category not on the list", OVER_PURCHASE_MAX: "Over the per-purchase cap",
  OVER_LENGTH: "Over the length", VELOCITY_LIMIT: "Too many purchases this hour", OVER_SLACK: "Over the slack",
  QUIET_HOURS: "Quiet hours", TASK_MISMATCH: "Doesn't fit the task", PRICE_SPIKE: "Price spike",
  DUPLICATE_REQUEST: "Looks like a duplicate",
};

/** The card's one-line summary. */
export function headline(e: LedgerEntry): string {
  const top = rankReasons(e.decision.reasons)[0];
  switch (e.decision.outcome) {
    case "BLOCK":
      if (top?.code === "PROMPT_INJECTION") return "Blocked: the page tried to hijack the agent.";
      if (top?.code === "LOOKALIKE_MERCHANT") return "Blocked: the shop is imitating a real one.";
      if (top?.code === "AGENT_REVOKED") return "Blocked: this agent has been snapped back.";
      return "Blocked: this purchase breaks the leash.";
    case "TUG":
      return "Tugged: Leash asked you before paying.";
    default:
      return e.decision.reasons.length ? "Approved." : "Approved: inside every rule on the leash.";
  }
}

export { riskLevel };
