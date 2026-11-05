import { useCallback, useEffect, useRef, useState } from "react";
import type { LeashCard, LeashChange, LedgerEntry, SlackSuggestion, WalkReport } from "../../types";
import { acceptSuggestion, dismissSuggestion, fetchWalkReports } from "../../api";
import { money, usedPercent } from "../../lib/format";
import { dateTimeUk, PERIOD_WORD } from "../../lib/status";
import { weekLabel, wholeMoney } from "../../lib/walkReport";

/**
 * The weekly walk report: one card per agent with what it spent against its length, how its requests went, the
 * blocks worth knowing about, and any slack Leash suggests. Reloads when the ledger or leashes change.
 */
export function WalkReports({ ledger, leashes, onChanged }: {
  ledger: LedgerEntry[];
  leashes: LeashCard[];
  onChanged: () => void;
}) {
  const [reports, setReports] = useState<WalkReport[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const first = useRef(true);

  const load = useCallback(() => {
    fetchWalkReports()
      .then((r) => { setReports(r); setError(null); })
      .catch((e: Error) => setError(e.message));
  }, []);

  // Live: a new ledger row or leash change re-reads the reports (debounced; the server caches the AI wording).
  useEffect(() => {
    const t = window.setTimeout(load, first.current ? 0 : 400);
    first.current = false;
    return () => window.clearTimeout(t);
  }, [ledger, leashes, load]);

  if (error) return <p className="notice" role="alert">Couldn't load the walk reports: {error}</p>;
  if (!reports) return <p className="empty">Writing this week's walk reports…</p>;
  if (reports.length === 0) return <p className="empty">No leashes yet, so nothing to report.</p>;

  const changed = () => { load(); onChanged(); };
  return (
    <div className="stack">
      <p className="muted small walk-intro">
        This week so far, from Monday 00:00 UK time. Leash works out every number from the ledger; where you see{" "}
        <span className="ai-tag">AI</span>, the AI only put those numbers into words.
      </p>
      {reports.map((r) => <ReportCard key={r.agentId} report={r} onChanged={changed} />)}
    </div>
  );
}

function ReportCard({ report: r, onChanged }: { report: WalkReport; onChanged: () => void }) {
  const left = Math.max(0, r.lengthMinor - r.usedMinor - r.pendingMinor);
  const moreBlocks = r.blocked - r.notableBlocks.length;
  // A quiet week with nothing to act on: the sentence and the length say it all, so skip the empty tiles.
  const quiet = r.requests === 0 && !r.suggestion && r.changes.length === 0;
  return (
    <article className={`card walk-card id-${r.agentKind}`} aria-labelledby={`walk-${r.agentId}`}>
      <div className="card-head">
        <div>
          <h3 id={`walk-${r.agentId}`}><i className="id-mark" />{r.agentName}</h3>
          <p className="sub">Week of {weekLabel(r.from)} · so far</p>
        </div>
        {r.revoked && <span className="pill out-BLOCK">Snapped back</span>}
      </div>

      <div className={`walk-body ${quiet ? "is-quiet" : ""}`}>
        <div className="walk-main">
          <p className="walk-summary">
            {r.summaryByAi && <span className="ai-tag" title="Worded by the AI from Leash's own figures">AI</span>}
            {r.summary}
          </p>

          {!quiet && <dl className="walk-stats">
            <Stat label="Approved" value={r.approved} tone="APPROVE" />
            <Stat label="Paid" value={r.paid} tone="APPROVE" note={r.paid ? money(r.paidMinor, r.currency) : undefined} />
            <Stat label="Tugged" value={r.tugged} tone="TUG" />
            <Stat label="Blocked" value={r.blocked} tone="BLOCK" />
          </dl>}

          <div className="length" aria-label={`${r.agentName}: length used`}>
            <div className="length-head">
              <span className="label">Length</span>
              <span><span className="num">{money(r.lengthMinor, r.currency)}</span> <span className="dim">{PERIOD_WORD[r.period]}</span></span>
            </div>
            <div className="length-bar">
              <span className="length-paid" style={{ width: `${usedPercent(r.usedMinor, r.lengthMinor)}%` }} />
              <span className="length-pending" style={{ width: `${usedPercent(r.pendingMinor, r.lengthMinor)}%` }} />
            </div>
            <p className="xs dim">
              <span className="num">{money(r.usedMinor, r.currency)}</span> used · <span className="num">{money(r.pendingMinor, r.currency)}</span> waiting ·{" "}
              <span className="num">{money(left, r.currency)}</span> left
              {r.refunded > 0 && <> · {r.refunded} refunded</>}
            </p>
          </div>
        </div>

        {!quiet && <div className="walk-side">
          {r.suggestion && <Suggestion s={r.suggestion} agentName={r.agentName} onChanged={onChanged} />}

          <section aria-label="Blocked this week">
            <h4 className="label walk-h">Blocked this week</h4>
            {r.notableBlocks.length === 0 ? (
              <p className="small dim">Nothing blocked.</p>
            ) : (
              <ul className="walk-blocks">
                {r.notableBlocks.map((b) => (
                  <li key={b.entryId} className="walk-block">
                    <div className="walk-block-top">
                      <span className="num walk-block-amt">{money(b.amountMinor, b.currency)}</span>
                      <span className="walk-block-item">{b.description}</span>
                    </div>
                    <p className="small">{b.reason}</p>
                    {b.evidence && <p className="rc-evidence-inline">“{b.evidence}”</p>}
                    <p className="xs dim">{b.merchant} · {dateTimeUk(b.at)}</p>
                  </li>
                ))}
              </ul>
            )}
            {moreBlocks > 0 && (
              <p className="xs dim">and {moreBlocks} more in the <a href={`#/app/ledger?agent=${encodeURIComponent(r.agentId)}`}>ledger</a>.</p>
            )}
          </section>

          {r.changes.length > 0 && (
            <section aria-label="Leash changes this week">
              <h4 className="label walk-h">Leash changes</h4>
              <ChangeList changes={r.changes} />
            </section>
          )}
        </div>}
      </div>
    </article>
  );
}

function Stat({ label, value, tone, note }: { label: string; value: number; tone: "APPROVE" | "TUG" | "BLOCK" | "NEUTRAL"; note?: string }) {
  return (
    <div className={`walk-stat out-${tone} ${value === 0 ? "is-zero" : ""}`}>
      <dt>{label}</dt>
      <dd className="num">{value}</dd>
      {note && <dd className="xs dim num walk-stat-note">{note}</dd>}
    </div>
  );
}

/** The suggestion: the AI may word the sentence, but the button's number is Leash's. One click applies it. */
function Suggestion({ s, agentName, onChanged }: { s: SlackSuggestion; agentName: string; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const to = wholeMoney(s.toMinor, s.currency);
  const act = (call: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    call().then(onChanged).catch((e: Error) => { setError(e.message); setBusy(false); });
  };
  const range = s.lowestApprovedMinor === s.highestApprovedMinor
    ? `at ${wholeMoney(s.lowestApprovedMinor, s.currency)}`
    : `from ${wholeMoney(s.lowestApprovedMinor, s.currency)} to ${wholeMoney(s.highestApprovedMinor, s.currency)}`;
  return (
    <section className="suggest" aria-label={`Suggested change for ${agentName}`}>
      <div className="suggest-head">
        <span className="label">Suggestion</span>
        {s.byAi && <span className="ai-tag" title="The AI wrote this sentence; Leash worked out the numbers">AI</span>}
      </div>
      <p className="suggest-text">{s.sentence}</p>
      <p className="xs dim">
        Slack <span className="num">{wholeMoney(s.fromMinor, s.currency)}</span> → <span className="num">{to}</span>, from{" "}
        {s.approvals} tugs you approved {range}, none turned down.
      </p>
      <div className="entry-actions">
        <button type="button" className="btn btn-primary" disabled={busy}
                onClick={() => act(() => acceptSuggestion(s.agentId, s.toMinor))}>
          {busy ? "Saving…" : `Raise slack to ${to}`}
        </button>
        <button type="button" className="btn btn-ghost" disabled={busy}
                onClick={() => act(() => dismissSuggestion(s.agentId, s.toMinor))}>Not now</button>
      </div>
      {error && <p className="form-error" role="alert">{error}</p>}
    </section>
  );
}

/** Accepted leash changes, newest first. Used by the walk report and under the ledger. */
export function ChangeList({ changes, agentName }: { changes: LeashChange[]; agentName?: (id: string) => string }) {
  return (
    <ul className="change-list">
      {changes.map((c) => (
        <li key={c.id} className="change">
          <div className="change-top">
            <span className="change-what">
              Slack <span className="num">{wholeMoney(c.fromMinor, c.currency)}</span> → <span className="num">{wholeMoney(c.toMinor, c.currency)}</span>
            </span>
            <span className="xs dim num">{dateTimeUk(c.createdAt)}</span>
          </div>
          <p className="xs dim">{agentName ? `${agentName(c.agentId)} · ` : ""}{c.evidence}</p>
        </li>
      ))}
    </ul>
  );
}
