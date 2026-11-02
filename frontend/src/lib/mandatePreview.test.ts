import { describe, expect, it } from "vitest";
import { previewMandate } from "./mandatePreview";

describe("previewMandate", () => {
  it("reads the demo grocery mandate", () => {
    expect(previewMandate("Groceries, £60 a week, supermarkets only, ask me over £25")).toEqual({
      lengthMinor: 6000,
      period: "week",
      slackMinor: 2500,
      shops: "Supermarkets",
    });
  });

  it("handles per-month and decimals", () => {
    const p = previewMandate("Office supplies £300 per month, ask me above £49.99");
    expect(p.lengthMinor).toBe(30000);
    expect(p.period).toBe("month");
    expect(p.slackMinor).toBe(4999);
  });

  it("leaves unknowns empty instead of guessing", () => {
    expect(previewMandate("buy whatever")).toEqual({ lengthMinor: null, period: null, slackMinor: null, shops: null });
  });
});
