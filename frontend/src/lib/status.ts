import type { EntryStatus, Outcome } from "../types";

/** What happened to the money, in words. Paired with the outcome pill (what Leash decided). */
export const STATUS_LABEL: Record<EntryStatus, string> = {
  BLOCKED: "Blocked",
  TUG_PENDING: "Waiting for you",
  DENIED: "You said no",
  PROCESSING: "Paying…",
  AWAITING_PAYER: "Confirm in PayPal",
  PAID: "Paid via PayPal",
  PAYPAL_OFF: "Approved (PayPal off)",
  PAYMENT_FAILED: "PayPal failed",
  CANCELLED: "Cancelled in PayPal",
  REFUNDED: "Refunded",
};

/** The tone a status reads in: same three outcome colours, never burgundy. */
export function statusTone(s: EntryStatus): Outcome | "NEUTRAL" {
  switch (s) {
    case "PAID":
    case "PAYPAL_OFF":
      return "APPROVE";
    case "TUG_PENDING":
    case "AWAITING_PAYER":
    case "PROCESSING":
      return "TUG";
    case "BLOCKED":
    case "PAYMENT_FAILED":
      return "BLOCK";
    default:
      return "NEUTRAL";
  }
}

/** "00:00:00" (how the API sends LocalTime) → "00:00". */
export function hhmm(t: string | null | undefined): string {
  return t ? t.slice(0, 5) : "";
}

/** Merchant and category sets arrive lower-cased (matching is case-insensitive); title-case them for display. */
export function titleCase(s: string): string {
  return s.replace(/(^|[\s-])([a-z])/g, (_, sep: string, c: string) => sep + c.toUpperCase());
}

export const PERIOD_WORD = { DAY: "a day", WEEK: "a week", MONTH: "a month", TOTAL: "in total" } as const;

/** Risk findings explain the "why" best, so they lead the reason list; the rest keep the engine's order. */
const RISK_FIRST = ["PROMPT_INJECTION", "LOOKALIKE_MERCHANT", "AI_RISK_HIGH", "AI_RISK_MEDIUM", "TASK_MISMATCH", "PRICE_SPIKE", "DUPLICATE_REQUEST"];
export function riskFirst<T extends { code: string }>(reasons: T[]): T[] {
  return [...reasons].sort((a, b) => (RISK_FIRST.includes(b.code) ? 1 : 0) - (RISK_FIRST.includes(a.code) ? 1 : 0));
}

export function reasonLabel(code: string): string {
  return code.toLowerCase().replaceAll("_", " ");
}

export function timeUk(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", timeZone: "Europe/London" });
}

export function dateTimeUk(iso: string): string {
  return new Date(iso).toLocaleString("en-GB", {
    day: "numeric",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
    timeZone: "Europe/London",
  });
}
