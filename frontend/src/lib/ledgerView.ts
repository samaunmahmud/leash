import type { EntryStatus, LedgerEntry } from "../types";

export type RiskLevel = "HIGH" | "MEDIUM" | "LOW" | "NONE";

/** Risk signals the heuristics raise (no AI): the ones that block, and the ones that ask. */
const RULE_RISK_HIGH = ["PROMPT_INJECTION", "LOOKALIKE_MERCHANT"];
const RULE_RISK_MEDIUM = ["TASK_MISMATCH", "PRICE_SPIKE", "DUPLICATE_REQUEST"];

/**
 * The risk level for the ledger's risk column. The AI's level wins when it ran (HIGH / MEDIUM from its rule codes,
 * LOW when it explained without objecting); otherwise the heuristic signals give the level. NONE: no risk check said
 * anything (the AI didn't run and no heuristic fired).
 */
export function riskLevel(e: LedgerEntry): { level: RiskLevel; byAi: boolean } {
  const codes = e.decision.reasons.map((r) => r.code);
  if (codes.includes("AI_RISK_HIGH")) return { level: "HIGH", byAi: true };
  const rulesHigh = codes.some((c) => RULE_RISK_HIGH.includes(c));
  if (rulesHigh) return { level: "HIGH", byAi: false };
  if (codes.includes("AI_RISK_MEDIUM")) return { level: "MEDIUM", byAi: true };
  if (codes.some((c) => RULE_RISK_MEDIUM.includes(c))) return { level: "MEDIUM", byAi: false };
  if (e.riskExplanation) return { level: "LOW", byAi: true };
  return { level: "NONE", byAi: false };
}

const PAID: EntryStatus[] = ["PAID", "PAYPAL_OFF"];
const WAITING: EntryStatus[] = ["TUG_PENDING", "AWAITING_PAYER", "PROCESSING"];

/** The pinned total row: money paid, money waiting on the human or PayPal, money Leash refused. Minor units. */
export function ledgerTotals(entries: LedgerEntry[]): { paid: number; waiting: number; blocked: number; currency: string; count: number } {
  let paid = 0, waiting = 0, blocked = 0;
  for (const e of entries) {
    const a = e.request.amountMinor;
    if (e.decision.outcome === "BLOCK" || e.status === "BLOCKED") blocked += a;
    else if (e.status && PAID.includes(e.status)) paid += a;
    else if (e.status && WAITING.includes(e.status)) waiting += a;
  }
  return { paid, waiting, blocked, currency: entries[0]?.request.currency ?? "GBP", count: entries.length };
}

/** "#/app/ledger?agent=agent-larder" → "agent-larder". */
export function agentFromHash(hash: string): string | null {
  const q = hash.split("?")[1];
  return q ? new URLSearchParams(q).get("agent") : null;
}

/** The text quick search looks in: what's shown plus every reason and the AI's sentence. */
export function searchText(e: LedgerEntry, agentName?: string): string {
  return [e.request.description, e.request.merchant, e.request.category, agentName, e.riskExplanation, e.paypalOrderId,
    ...e.decision.reasons.map((r) => r.message)].filter(Boolean).join(" ").toLowerCase();
}

/** Every word of the query must appear (like AG Grid's quick filter), so totals match the rows shown. */
export function matchesSearch(e: LedgerEntry, query: string, agentName?: string): boolean {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return true;
  const text = searchText(e, agentName);
  return words.every((w) => text.includes(w));
}
