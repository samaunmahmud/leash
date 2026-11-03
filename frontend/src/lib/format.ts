import type { Outcome } from "../types";

/** 1840, "GBP" → "£18.40". Money arrives in minor units; this is the only place it becomes a decimal. */
export function money(minor: number, currency: string): string {
  try {
    return new Intl.NumberFormat("en-GB", { style: "currency", currency }).format(minor / 100);
  } catch {
    return `${(minor / 100).toFixed(2)} ${currency}`;
  }
}

/** What each outcome is called on screen. Same words everywhere: UI, ledger, video. */
export const OUTCOME_LABEL: Record<Outcome, string> = {
  APPROVE: "Approved",
  TUG: "Tugged",
  BLOCK: "Blocked",
};

/** Share of a leash used, clamped to 0–100 for the length bar. */
export function usedPercent(spentMinor: number, limitMinor: number): number {
  if (limitMinor <= 0) return 100;
  return Math.max(0, Math.min(100, Math.round((spentMinor / limitMinor) * 100)));
}
