import { describe, expect, it } from "vitest";
import { parseRoute } from "./useRoute";

describe("parseRoute", () => {
  it("routes #/app tabs and defaults to leashes", () => {
    expect(parseRoute("#/app")).toEqual({ page: "app", tab: "leashes" });
    expect(parseRoute("#/app/tugs")).toEqual({ page: "app", tab: "tugs" });
    expect(parseRoute("#/app/ledger")).toEqual({ page: "app", tab: "ledger" });
    expect(parseRoute("#/app/nonsense")).toEqual({ page: "app", tab: "leashes" });
  });

  it("leaves landing anchors to the landing page", () => {
    expect(parseRoute("")).toEqual({ page: "landing" });
    expect(parseRoute("#ledger")).toEqual({ page: "landing" });
    expect(parseRoute("#how")).toEqual({ page: "landing" });
  });
});
