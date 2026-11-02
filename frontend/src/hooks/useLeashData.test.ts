import { describe, expect, it } from "vitest";
import { upsertEntry } from "./useLeashData";
import type { LedgerEntry } from "../types";

const entry = (id: string, status: LedgerEntry["status"]): LedgerEntry => ({
  id,
  status,
  request: {
    id,
    agentId: "a",
    mandateId: "m",
    merchant: "Larder",
    category: "groceries",
    description: "x",
    amountMinor: 100,
    currency: "GBP",
    task: null,
    reason: null,
    sourceUrl: null,
    requestedAt: "2026-11-09T12:00:00Z",
  },
  decision: { requestId: id, outcome: "APPROVE", reasons: [] },
});

describe("upsertEntry", () => {
  it("puts new entries on top", () => {
    const list = upsertEntry([entry("a", "PAID")], entry("b", "AWAITING_PAYER"));
    expect(list.map((e) => e.id)).toEqual(["b", "a"]);
  });

  it("updates an existing entry in place", () => {
    const list = upsertEntry([entry("b", "AWAITING_PAYER"), entry("a", "PAID")], entry("b", "PAID"));
    expect(list.map((e) => `${e.id}:${e.status}`)).toEqual(["b:PAID", "a:PAID"]);
  });
});
