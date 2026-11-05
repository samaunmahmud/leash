import { money } from "./format";

/** "2026-11-09T00:00:00Z" → "Mon 9 Nov" (UK time, the zone Leash's weeks are counted in). */
export function weekLabel(iso: string): string {
  return new Date(iso).toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "Europe/London" });
}

/** £30.00 → "£30", £26.50 → "£26.50": how a person says a limit. */
export function wholeMoney(minor: number, currency: string): string {
  const s = money(minor, currency);
  return minor % 100 === 0 ? s.replace(/\.00$/, "") : s;
}
