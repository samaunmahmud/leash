import { describe, expect, it } from "vitest";
import type { LedgerEntry, Outcome } from "../types";
import { agentFromHash, ledgerTotals, matchesSearch, riskLevel } from "./ledgerView";

const e = (outcome: Outcome, codes: string[], extra: Partial<LedgerEntry> = {}): LedgerEntry => ({
  id: Math.random().toString(),
  request: { id: "r", agentId: "a", mandateId: "m", merchant: "Larder", category: "groceries", description: "x",
    amountMinor: 1000, currency: "GBP", task: null, reason: null, sourceUrl: null, requestedAt: "2026-11-09T12:00:00Z" },
  decision: { requestId: "r", outcome, reasons: codes.map((code) => ({ code, message: code })) },
  ...extra,
});

describe("riskLevel", () => {
  it("prefers the AI's verdict and labels it", () => {
    expect(riskLevel(e("BLOCK", ["CATEGORY_BLOCKED", "AI_RISK_HIGH"]))).toEqual({ level: "HIGH", byAi: true });
    expect(riskLevel(e("TUG", ["AI_RISK_MEDIUM"]))).toEqual({ level: "MEDIUM", byAi: true });
    expect(riskLevel(e("APPROVE", [], { riskExplanation: "Ordinary groceries." }))).toEqual({ level: "LOW", byAi: true });
  });
  it("falls back to the rule-based signals when the AI didn't run", () => {
    expect(riskLevel(e("BLOCK", ["PROMPT_INJECTION"]))).toEqual({ level: "HIGH", byAi: false });
    expect(riskLevel(e("TUG", ["PRICE_SPIKE"]))).toEqual({ level: "MEDIUM", byAi: false });
    expect(riskLevel(e("APPROVE", []))).toEqual({ level: "NONE", byAi: false });
  });
  it("never lets a quiet AI hide a rule-based high", () => {
    expect(riskLevel(e("BLOCK", ["PROMPT_INJECTION"], { riskExplanation: "Looks fine." })).level).toBe("HIGH");
  });
});

describe("ledgerTotals", () => {
  it("splits paid, waiting and blocked", () => {
    const t = ledgerTotals([
      e("APPROVE", [], { status: "PAID" }),
      e("APPROVE", [], { status: "AWAITING_PAYER" }),
      e("TUG", [], { status: "TUG_PENDING" }),
      e("TUG", [], { status: "DENIED" }),
      e("BLOCK", ["PROMPT_INJECTION"], { status: "BLOCKED" }),
    ]);
    expect(t).toEqual({ paid: 1000, waiting: 2000, blocked: 1000, currency: "GBP", count: 5 });
  });
});

describe("agentFromHash", () => {
  it("reads the agent filter from the route", () => {
    expect(agentFromHash("#/app/ledger?agent=agent-larder")).toBe("agent-larder");
    expect(agentFromHash("#/app/ledger")).toBeNull();
  });
});

describe("matchesSearch", () => {
  it("finds words in reasons and the agent name, all words required", () => {
    const x = e("BLOCK", ["PROMPT_INJECTION"]);
    x.decision.reasons[0].message = "The page hides text from people";
    expect(matchesSearch(x, "hides page", "Larder bot")).toBe(true);
    expect(matchesSearch(x, "larder")).toBe(true); // the merchant
    expect(matchesSearch(x, "larder bot")).toBe(false); // "bot" is only in the agent name, not passed here
    expect(matchesSearch(x, "hides tesco")).toBe(false);
    expect(matchesSearch(x, "  ")).toBe(true);
  });
});
