/**
 * Placeholder parser for the landing-page composer: pulls the obvious numbers out of a plain-English mandate so the
 * preview isn't static. The real parser is the LLM MandateParser on the backend (sam_data.md T06); this stays as the
 * offline fallback.
 */
export interface MandatePreview {
  lengthMinor: number | null;
  period: "day" | "week" | "month" | "trip" | null;
  slackMinor: number | null;
  shops: string | null;
}

const POUNDS = /£\s?(\d+(?:\.\d{1,2})?)/;

function toMinor(s: string): number {
  return Math.round(parseFloat(s) * 100);
}

export function previewMandate(text: string): MandatePreview {
  const t = text.toLowerCase();

  const length = t.match(/£\s?(\d+(?:\.\d{1,2})?)\s*(?:a|per|\/|each)\s*(day|week|month|trip)/);
  const slack = t.match(/(?:over|above|more than)\s*£\s?(\d+(?:\.\d{1,2})?)/);
  const shops = t.match(/(supermarkets?|[a-z' ]+?) only/);
  const anyAmount = length ? null : t.match(POUNDS);

  return {
    lengthMinor: length ? toMinor(length[1]) : anyAmount ? toMinor(anyAmount[1]) : null,
    period: length ? (length[2] as MandatePreview["period"]) : null,
    slackMinor: slack ? toMinor(slack[1]) : null,
    shops: shops ? shops[1].trim().replace(/^\w/, (c) => c.toUpperCase()) : null,
  };
}
