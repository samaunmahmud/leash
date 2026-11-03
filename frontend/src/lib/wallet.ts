import type { WalletView } from "../types";

export type WalletAction = "save" | "approve" | "complete" | "cancel" | "forget";

/** Which buttons the "pay while you're away" card shows, and its status label. Pure, so it's unit-tested. */
export function walletState(w: WalletView): { label: string; actions: WalletAction[] } {
  switch (w.status) {
    case "ACTIVE":
      return { label: "On", actions: ["forget"] };
    case "PENDING":
      return { label: "Waiting for you", actions: [...(w.approveUrl ? (["approve"] as const) : []), "complete", "cancel"] };
    default:
      return { label: "Off", actions: w.paypalConfigured ? ["save"] : [] };
  }
}
