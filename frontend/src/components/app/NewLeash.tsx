import { useEffect, useState } from "react";
import type { MandateInput, Period } from "../../types";
import { ApiError, createLeash, parseMandate } from "../../api";
import { previewMandate } from "../../lib/mandatePreview";
import { minorToInput, parseMinor, splitList } from "../../lib/money";
import { hhmm } from "../../lib/status";

const EXAMPLES = [
  "Groceries, £60 a week, supermarkets only, ask me over £25. Never gift cards.",
  "Office supplies, £300 a month from Viking or Staples, ask me over £50, no electronics.",
  "Book the Edinburgh trip: £400 in total, airlines, hotels and rail only, ask me over £100, nothing between midnight and 6am.",
];

/** Form state: strings, exactly as typed. Converted to minor units only on save (parseMinor, no floats). */
interface Draft {
  agentName: string;
  kind: string;
  name: string;
  length: string;
  period: Period;
  slack: string;
  cap: string;
  merchants: string;
  categories: string;
  blocked: string;
  perHour: string;
  quietStart: string;
  quietEnd: string;
}

const EMPTY: Draft = {
  agentName: "", kind: "other", name: "", length: "", period: "WEEK", slack: "", cap: "",
  merchants: "", categories: "", blocked: "gift cards", perHour: "", quietStart: "", quietEnd: "",
};

function fromInput(m: MandateInput, agentName: string | null, kind: string | null): Draft {
  return {
    agentName: agentName ?? "",
    kind: kind ?? "other",
    name: m.name,
    length: minorToInput(m.periodLimitMinor),
    period: m.period,
    slack: minorToInput(m.autoApproveUpToMinor),
    cap: m.perPurchaseMaxMinor ? minorToInput(m.perPurchaseMaxMinor) : "",
    merchants: m.allowedMerchants.join(", "),
    categories: m.allowedCategories.join(", "),
    blocked: m.blockedCategories.join(", "),
    perHour: m.maxPurchasesPerHour ? String(m.maxPurchasesPerHour) : "",
    quietStart: hhmm(m.quietStart),
    quietEnd: hhmm(m.quietEnd),
  };
}

/** Offline fallback when the API's parser isn't reachable: the landing page's regex preview. */
function fromPreview(text: string): Draft {
  const p = previewMandate(text);
  const period: Period = p.period === "day" ? "DAY" : p.period === "month" ? "MONTH" : p.period === "trip" ? "TOTAL" : "WEEK";
  const supermarkets = p.shops?.toLowerCase().startsWith("supermarket");
  return {
    ...EMPTY,
    name: text.split(/[,.:]/)[0].trim().slice(0, 60),
    length: p.lengthMinor ? minorToInput(p.lengthMinor) : "",
    period,
    slack: p.slackMinor ? minorToInput(p.slackMinor) : "",
    categories: supermarkets ? "groceries, household" : "",
    merchants: p.shops && !supermarkets ? p.shops : "",
    kind: supermarkets ? "grocery" : "other",
  };
}

export function toMandateInput(d: Draft, sourceText: string): { input?: MandateInput; error?: string } {
  const length = parseMinor(d.length);
  const slack = d.slack.trim() === "" ? 0 : parseMinor(d.slack);
  const cap = d.cap.trim() === "" ? 0 : parseMinor(d.cap);
  if (!d.agentName.trim()) return { error: "Give the agent a name." };
  if (!d.name.trim()) return { error: "Give the leash a name." };
  if (length === null || length === 0) return { error: "The length must be an amount like 60 or 60.50." };
  if (slack === null) return { error: "Slack must be an amount like 25." };
  if (cap === null) return { error: "The per-purchase cap must be an amount, or blank for none." };
  if (slack > length) return { error: "Slack can't be more than the length." };
  if ((d.quietStart === "") !== (d.quietEnd === "")) return { error: "Quiet hours need a start and an end." };
  const perHour = d.perHour.trim() === "" ? 0 : Number.parseInt(d.perHour, 10);
  if (!Number.isInteger(perHour) || perHour < 0) return { error: "Purchases per hour must be a whole number." };
  return {
    input: {
      name: d.name.trim(),
      currency: "GBP",
      period: d.period,
      periodLimitMinor: length,
      autoApproveUpToMinor: slack,
      perPurchaseMaxMinor: cap,
      allowedMerchants: splitList(d.merchants),
      blockedMerchants: [],
      allowedCategories: splitList(d.categories).map((c) => c.toLowerCase()),
      blockedCategories: splitList(d.blocked).map((c) => c.toLowerCase()),
      maxPurchasesPerHour: perHour,
      quietStart: d.quietStart ? hhmm(d.quietStart) : null,
      quietEnd: d.quietEnd ? hhmm(d.quietEnd) : null,
      active: true,
      sourceText: sourceText.trim() || null,
    },
  };
}

/**
 * Write a mandate in plain English, let Leash read it into rules, check them, then save. The AI only proposes:
 * nothing is saved until the human confirms the form, and the server validates it like any other input.
 */
/** Text handed over from the landing page's composer (#/app/new?text=...). */
function initialText(): string {
  const q = window.location.hash.split("?")[1];
  return q ? new URLSearchParams(q).get("text") ?? "" : "";
}

export function NewLeash({ onCreated }: { onCreated: () => void }) {
  const [text, setText] = useState(initialText);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [source, setSource] = useState<"ai" | "heuristic" | "offline" | null>(null);
  const [notes, setNotes] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const read = (value: string) => {
    if (!value.trim()) return;
    setBusy(true);
    setError(null);
    parseMandate(value)
      .then((p) => {
        setDraft(fromInput(p.mandate, p.agentName, p.kind));
        setSource(p.source);
        setNotes(p.notes);
      })
      .catch((e: unknown) => {
        // 400: the text didn't make sense. 401/403: the owner gate, not the AI: say so rather than fall back.
        if (e instanceof ApiError && (e.status === 400 || e.code === "OWNER_REQUIRED" || e.code === "OWNER_DISABLED")) {
          setError(e.message);
          return;
        }
        setDraft(fromPreview(value));
        setSource("offline");
        setNotes([]);
      })
      .finally(() => setBusy(false));
  };

  const save = () => {
    if (!draft) return;
    const { input, error: err } = toMandateInput(draft, text);
    if (!input) return setError(err ?? "Check the form.");
    setBusy(true);
    setError(null);
    createLeash(draft.agentName.trim(), draft.kind, input)
      .then(() => {
        setDraft(null);
        setText("");
        onCreated();
        window.location.hash = "#/app/leashes";
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  // Arriving from the landing page with text: read it straight away.
  useEffect(() => {
    if (text) read(text);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const set = (k: keyof Draft) => (ev: { target: { value: string } }) =>
    setDraft((d) => (d ? { ...d, [k]: ev.target.value } : d));

  return (
    <div className="new-leash">
      <form className="composer" onSubmit={(e) => { e.preventDefault(); read(text); }}>
        <label htmlFor="mandate-text" className="sr-only">Describe the leash in plain English</label>
        <input id="mandate-text" value={text} onChange={(e) => setText(e.target.value)} autoComplete="off"
               placeholder="Groceries, £60 a week, supermarkets only, ask me over £25" />
        <button type="submit" className="btn btn-primary" disabled={busy || !text.trim()}>Read it</button>
      </form>
      <div className="examples">
        <span className="xs dim">Try</span>
        {EXAMPLES.map((ex) => (
          <button key={ex} type="button" className="chip" onClick={() => { setText(ex); read(ex); }}>{ex.split(",")[0]}</button>
        ))}
      </div>

      {error && <p className="form-error" role="alert">{error}</p>}

      {draft && (
        <form className="card mandate-form" onSubmit={(e) => { e.preventDefault(); save(); }}>
          <div className="card-head">
            <div>
              <h3>Check the rules</h3>
              <p className="sub">
                {source === "ai" && "Read by the AI parser. Nothing is saved until you confirm."}
                {source === "heuristic" && "Read without AI (no model configured). Check every field."}
                {source === "offline" && "A rough reading on this device (the AI parser isn't available). Check every field."}
              </p>
            </div>
          </div>
          {notes.length > 0 && <ul className="notes">{notes.map((n) => <li key={n}>{n}</li>)}</ul>}
          <div className="fields">
            <Field label="Agent name"><input value={draft.agentName} onChange={set("agentName")} placeholder="Gift bot" required /></Field>
            <Field label="Kind">
              <select value={draft.kind} onChange={set("kind")}>
                <option value="grocery">Groceries</option><option value="office">Office</option>
                <option value="travel">Travel</option><option value="other">Other</option>
              </select>
            </Field>
            <Field label="Leash name" wide><input value={draft.name} onChange={set("name")} required /></Field>
            <Field label="Length (£)"><input inputMode="decimal" value={draft.length} onChange={set("length")} required /></Field>
            <Field label="Per">
              <select value={draft.period} onChange={set("period")}>
                <option value="DAY">Day</option><option value="WEEK">Week</option>
                <option value="MONTH">Month</option><option value="TOTAL">In total</option>
              </select>
            </Field>
            <Field label="Slack: auto-pay up to (£)"><input inputMode="decimal" value={draft.slack} onChange={set("slack")} /></Field>
            <Field label="Cap per purchase (£)" hint="Blank for none"><input inputMode="decimal" value={draft.cap} onChange={set("cap")} /></Field>
            <Field label="Only these shops" hint="Comma-separated; blank for any" wide><input value={draft.merchants} onChange={set("merchants")} /></Field>
            <Field label="Only these categories" hint="Blank for any" wide><input value={draft.categories} onChange={set("categories")} /></Field>
            <Field label="Never these categories" wide><input value={draft.blocked} onChange={set("blocked")} /></Field>
            <Field label="Max purchases an hour" hint="Blank for no limit"><input inputMode="numeric" value={draft.perHour} onChange={set("perHour")} /></Field>
            <Field label="Quiet from"><input type="time" value={draft.quietStart} onChange={set("quietStart")} /></Field>
            <Field label="Quiet until"><input type="time" value={draft.quietEnd} onChange={set("quietEnd")} /></Field>
          </div>
          <div className="card-foot">
            <button type="submit" className="btn btn-primary" disabled={busy}>Confirm and leash it</button>
            <button type="button" className="btn btn-ghost" onClick={() => setDraft(null)}>Start again</button>
          </div>
        </form>
      )}
    </div>
  );
}

function Field({ label, hint, wide, children }: { label: string; hint?: string; wide?: boolean; children: JSX.Element }) {
  return (
    <label className={`field ${wide ? "wide" : ""}`}>
      <span className="label">{label}</span>
      {children}
      {hint && <span className="xs dim">{hint}</span>}
    </label>
  );
}
