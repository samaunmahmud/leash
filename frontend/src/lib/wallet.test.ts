import { describe, expect, it } from "vitest";
import { walletState } from "./wallet";

const view = (status: "NONE" | "PENDING" | "ACTIVE", extra: Partial<{ approveUrl: string | null; paypalConfigured: boolean }> = {}) => ({
  status, hint: null, approveUrl: null, paypalConfigured: true, ...extra,
});

describe("walletState", () => {
  it("offers to save PayPal when nothing is saved", () => {
    expect(walletState(view("NONE"))).toEqual({ label: "Off", actions: ["save"] });
  });
  it("offers nothing when PayPal isn't configured on the server", () => {
    expect(walletState(view("NONE", { paypalConfigured: false })).actions).toEqual([]);
  });
  it("shows the approve link while waiting, and a way to finish or cancel", () => {
    expect(walletState(view("PENDING", { approveUrl: "https://www.sandbox.paypal.com/x" })).actions).toEqual(["approve", "complete", "cancel"]);
    expect(walletState(view("PENDING")).actions).toEqual(["complete", "cancel"]);
  });
  it("only lets you forget an active wallet", () => {
    expect(walletState(view("ACTIVE"))).toEqual({ label: "On", actions: ["forget"] });
  });
});
