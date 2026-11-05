import { describe, expect, it } from "vitest";
import { weekLabel, wholeMoney } from "./walkReport";

describe("wholeMoney", () => {
  it("drops .00 but keeps pence", () => {
    expect(wholeMoney(3000, "GBP")).toBe("£30");
    expect(wholeMoney(2650, "GBP")).toBe("£26.50");
    expect(wholeMoney(100000, "GBP")).toBe("£1,000");
  });
});

describe("weekLabel", () => {
  it("names the Monday the week starts in UK time", () => {
    // Monday 00:00 in London during BST is Sunday 23:00 UTC.
    expect(weekLabel("2026-10-18T23:00:00Z")).toBe("Mon 19 Oct");
    expect(weekLabel("2026-11-09T00:00:00Z")).toBe("Mon 9 Nov");
  });
});
