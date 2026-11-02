import { describe, expect, it } from "vitest";
import { minorToInput, parseMinor, splitList } from "./money";

describe("parseMinor", () => {
  it("reads pounds and pence without floating point", () => {
    expect(parseMinor("60")).toBe(6000);
    expect(parseMinor("£60")).toBe(6000);
    expect(parseMinor("60.5")).toBe(6050);
    expect(parseMinor("19.99")).toBe(1999);
    expect(parseMinor("0.07")).toBe(7);
    expect(parseMinor(" 1,250.00 ")).toBe(125000);
  });

  it("rejects anything that isn't an amount", () => {
    expect(parseMinor("")).toBeNull();
    expect(parseMinor("abc")).toBeNull();
    expect(parseMinor("-5")).toBeNull();
    expect(parseMinor("1.234")).toBeNull();
    expect(parseMinor("1e3")).toBeNull();
  });
});

describe("minorToInput", () => {
  it("round-trips with parseMinor", () => {
    for (const v of [0, 7, 6000, 6050, 1999, 125000]) expect(parseMinor(minorToInput(v))).toBe(v);
  });
});

describe("splitList", () => {
  it("splits on commas and newlines and drops blanks", () => {
    expect(splitList("Tesco, Sainsbury's ,\n  ,Larder")).toEqual(["Tesco", "Sainsbury's", "Larder"]);
  });
});

import { hhmm, titleCase } from "./status";

describe("display helpers", () => {
  it("trims API times to HH:mm and title-cases sets", () => {
    expect(hhmm("00:00:00")).toBe("00:00");
    expect(hhmm(null)).toBe("");
    expect(titleCase("amazon business")).toBe("Amazon Business");
    expect(titleCase("sainsbury's")).toBe("Sainsbury's");
  });
});
