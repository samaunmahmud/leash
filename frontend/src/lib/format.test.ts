import { describe, expect, it } from "vitest";
import { money, usedPercent, OUTCOME_LABEL } from "./format";

describe("money", () => {
  it("formats minor units as pounds", () => {
    expect(money(1840, "GBP")).toBe("£18.40");
    expect(money(50000, "GBP")).toBe("£500.00");
  });
  it("falls back for unknown currencies", () => {
    expect(money(100, "XXXX")).toBe("1.00 XXXX");
  });
});

describe("usedPercent", () => {
  it("clamps and rounds", () => {
    expect(usedPercent(1840, 6000)).toBe(31);
    expect(usedPercent(9000, 6000)).toBe(100);
    expect(usedPercent(0, 0)).toBe(100);
  });
});

it("labels every outcome", () => {
  expect(Object.keys(OUTCOME_LABEL)).toEqual(["APPROVE", "TUG", "BLOCK"]);
});
