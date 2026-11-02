/**
 * "60", "£60", "60.5", "1,250.00" → minor units (6000, 6000, 6050, 125000), or null if it isn't a money amount.
 * String arithmetic only: never parseFloat on money, so "19.99" can't become 1998.9999.
 */
export function parseMinor(input: string): number | null {
  const s = input.trim().replace(/^£\s*/, "").replace(/,/g, "");
  const m = s.match(/^(\d{1,9})(?:\.(\d{1,2}))?$/);
  if (!m) return null;
  const pence = (m[2] ?? "").padEnd(2, "0");
  return Number(m[1]) * 100 + Number(pence);
}

/** 6050 → "60.50", for putting minor units back into an input box. */
export function minorToInput(minor: number): string {
  const whole = Math.trunc(minor / 100);
  const pence = Math.abs(minor % 100);
  return pence === 0 ? String(whole) : `${whole}.${String(pence).padStart(2, "0")}`;
}

/** "Tesco, Sainsbury's ,  " → ["Tesco", "Sainsbury's"]. */
export function splitList(input: string): string[] {
  return input
    .split(/[,\n]/)
    .map((s) => s.trim())
    .filter(Boolean);
}
