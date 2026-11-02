import { useState, type FormEvent } from "react";
import { money } from "../../lib/format";
import { previewMandate } from "../../lib/mandatePreview";
import { LeashGraph } from "./LeashGraph";

const EXAMPLES = [
  "Groceries, £60 a week, supermarkets only, ask me over £25",
  "Office supplies, £300 a month, ask me over £50",
  "Train and hotels, £400 a trip, ask me over £150",
];

export function Hero() {
  const [text, setText] = useState(EXAMPLES[0]);
  const [submitted, setSubmitted] = useState(false);
  const p = previewMandate(text);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    setSubmitted(true);
  };

  return (
    <section className="hero container">
      <div className="hero-copy">
        <p className="hero-kicker"><i className="live" aria-hidden="true" />Built on the PayPal sandbox</p>
        <h1>Let your agents shop. Keep them on a leash.</h1>
        <p className="hero-lede">
          Give each AI agent a spending mandate in plain English. Every purchase it tries is checked by fixed rules and
          an AI risk check, then paid through PayPal, sent to you to approve, or blocked, with the reason written down.
        </p>

        <form className="composer" onSubmit={submit}>
          <label htmlFor="mandate" className="sr-only">Describe the leash</label>
          <input id="mandate" value={text} onChange={(e) => { setText(e.target.value); setSubmitted(false); }}
                 placeholder="e.g. Groceries, £60 a week, ask me over £25" autoComplete="off" />
          <button className="btn btn-primary" type="submit">Leash an agent</button>
        </form>

        <div className="examples">
          <span className="dim small">Try</span>
          {EXAMPLES.slice(1).map((ex) => (
            <button key={ex} type="button" className="chip" onClick={() => { setText(ex); setSubmitted(false); }}>
              {ex.split(",")[0]}
            </button>
          ))}
        </div>

        <dl className={`preview ${submitted ? "is-confirmed" : ""}`} aria-live="polite">
          <div><dt>Length</dt><dd>{p.lengthMinor != null ? `${money(p.lengthMinor, "GBP")}${p.period ? ` a ${p.period}` : ""}` : "Not set"}</dd></div>
          <div><dt>Slack</dt><dd>{p.slackMinor != null ? `Auto-pay up to ${money(p.slackMinor, "GBP")}` : "Ask me every time"}</dd></div>
          <div><dt>Shops</dt><dd>{p.shops ?? "Any not blocked"}</dd></div>
        </dl>
        {submitted && (
          <p className="notice ok small">
            <span>Preview only for now. Saving leashes arrives with the mandate parser; the rules above are what Leash will enforce.</span>
          </p>
        )}
      </div>

      <LeashGraph />
    </section>
  );
}
