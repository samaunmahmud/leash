import { describe, expect, it } from "vitest";
import type { LedgerEntry, Outcome } from "../types";
import { decisionChain, headline, laneOf, rankReasons } from "./decision";

const e = (outcome: Outcome, codes: string[], riskExplanation: string | null = null): LedgerEntry => ({
  id: "x",
  request: { id: "x", agentId: "a", mandateId: "m", merchant: "Larder", category: "gift cards", description: "Gift cards",
    amountMinor: 50000, currency: "GBP", task: "Buy groceries", reason: "pre-approved", sourceUrl: "/larder/gift-bundle", requestedAt: "2026-11-09T12:00:00Z" },
  decision: { requestId: "x", outcome, reasons: codes.map((code) => ({ code, message: code })) },
  riskExplanation,
});

describe("decisionChain", () => {
  it("shows each check's own say and a final that is the strictest", () => {
    const c = decisionChain(e("BLOCK", ["OVER_SLACK", "AI_RISK_HIGH"], "Hidden instructions."));
    expect(c).toEqual({ rules: "TUG", risk: null, ai: { ran: true, level: "HIGH", outcome: "BLOCK" }, final: "BLOCK" });
  });
  it("the poisoned page: rules, risk checks and final all block", () => {
    const c = decisionChain(e("BLOCK", ["CATEGORY_BLOCKED", "OVER_LENGTH", "PROMPT_INJECTION", "TASK_MISMATCH"]));
    expect(c.rules).toBe("BLOCK");
    expect(c.risk).toBe("BLOCK");
    expect(c.ai).toEqual({ ran: false, level: "NONE", outcome: null });
  });
  it("an AI that says low risk is recorded as ran, without changing anything", () => {
    const c = decisionChain(e("APPROVE", [], "Ordinary groceries."));
    expect(c.ai).toEqual({ ran: true, level: "LOW", outcome: "APPROVE" });
    expect(c.final).toBe("APPROVE");
  });
});

describe("rankReasons", () => {
  it("puts injection first, blocks before tugs", () => {
    const ranked = rankReasons(e("BLOCK", ["OVER_SLACK", "CATEGORY_BLOCKED", "TASK_MISMATCH", "PROMPT_INJECTION", "OVER_LENGTH"]).decision.reasons);
    expect(ranked.map((r) => r.code)).toEqual(["PROMPT_INJECTION", "CATEGORY_BLOCKED", "OVER_LENGTH", "TASK_MISMATCH", "OVER_SLACK"]);
  });
});

describe("laneOf and headline", () => {
  it("knows who raised what", () => {
    expect(laneOf("PROMPT_INJECTION")).toBe("risk");
    expect(laneOf("AI_RISK_MEDIUM")).toBe("ai");
    expect(laneOf("OVER_LENGTH")).toBe("rules");
  });
  it("leads a hijack with the hijack", () => {
    expect(headline(e("BLOCK", ["CATEGORY_BLOCKED", "PROMPT_INJECTION"]))).toBe("Blocked: the page tried to hijack the agent.");
    expect(headline(e("TUG", ["OVER_SLACK"]))).toBe("Tugged: Leash asked you before paying.");
  });
});
