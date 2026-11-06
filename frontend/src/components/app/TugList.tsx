import { useState } from "react";
import type { LeashCard, TugView } from "../../types";
import { approveTug, denyTug } from "../../api";
import { money } from "../../lib/format";
import { reasonLabel, riskFirst, timeUk } from "../../lib/status";

/** Purchases waiting for the human. Approve pays through PayPal; deny frees the length straight away. */
export function TugList({ tugs, leashes, onChanged }: { tugs: TugView[]; leashes: LeashCard[]; onChanged: () => void }) {
  const [done, setDone] = useState<TugView | null>(null);
  if (tugs.length === 0) {
    return (
      <>
        {done && <Answered view={done} />}
        <p className="empty">
          Nothing waiting. Purchases above an agent's slack, or that look odd, show up here.
          {!done && <> Press <b>Run demo</b> to see one: Larder bot's £32 shop needs your nod.</>}
        </p>
      </>
    );
  }
  const agentName = (id: string) => leashes.find((l) => l.agent.id === id)?.agent;
  return (
    <>
      {done && <Answered view={done} />}
      <ul className="tug-list">
        {tugs.map((t) => (
          <TugItem key={t.tug.id} view={t} agent={agentName(t.entry.request.agentId)}
                   onAnswered={(v) => { setDone(v); onChanged(); }} />
        ))}
      </ul>
    </>
  );
}

function TugItem({ view, agent, onAnswered }: {
  view: TugView;
  agent?: { name: string; kind: string };
  onAnswered: (v: TugView) => void;
}) {
  const { entry, tug } = view;
  const r = entry.request;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const answer = (fn: (id: string) => Promise<TugView>) => {
    setBusy(true);
    setError(null);
    fn(tug.id)
      .then(onAnswered)
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  return (
    <li className="card tug out-TUG">
      <div className="tug-main">
        <p className="tug-who"><i className={`id-mark id-${agent?.kind ?? "other"}`} />{agent?.name ?? r.agentId} <span className="dim">· {timeUk(r.requestedAt)}</span></p>
        <p className="tug-what"><span className="num tug-amount">{money(r.amountMinor, r.currency)}</span> at <b>{r.merchant}</b></p>
        <p className="muted">{r.description}</p>
        <ul className="reasons compact">
          {riskFirst(entry.decision.reasons).map((x) => (
            <li key={x.code}><code className="reason-code">{reasonLabel(x.code)}</code><span>{x.message}</span></li>
          ))}
        </ul>
        {error && <p className="form-error" role="alert">{error}</p>}
      </div>
      <div className="tug-actions">
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => answer(approveTug)}>Approve</button>
        <button type="button" className="btn" disabled={busy} onClick={() => answer(denyTug)}>Deny</button>
      </div>
    </li>
  );
}

function Answered({ view }: { view: TugView }) {
  const e = view.entry;
  const what = `${money(e.request.amountMinor, e.request.currency)} at ${e.request.merchant}`;
  if (view.tug.status === "DENIED") return <p className="notice" role="status">Denied {what}. Nothing was paid.</p>;
  return (
    <p className="notice ok" role="status">
      Approved {what}.{" "}
      {e.approveUrl ? (
        <a className="pp-link" href={e.approveUrl} target="_blank" rel="noreferrer">Confirm it in PayPal sandbox</a>
      ) : e.status === "PAID" ? (
        "Paid via PayPal sandbox."
      ) : (
        e.statusDetail
      )}
    </p>
  );
}
