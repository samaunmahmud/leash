import { Fragment, useMemo, useState } from "react";
import type { LeashCard, LedgerEntry } from "../../types";
import { captureEntry } from "../../api";
import { money } from "../../lib/format";
import { dateTimeUk } from "../../lib/status";
import { ChevronIcon } from "../ui/Icons";
import { OutcomePill, StatusTag } from "../ui/OutcomePill";
import { ReasonCard } from "../ledger/ReasonCard";

/** Every request, newest first, with the reason card one click away. T13 swaps the table for AG Grid. */
export function LedgerGrid({ ledger, leashes, latestId, onChanged }: {
  ledger: LedgerEntry[];
  leashes: LeashCard[];
  latestId: string | null;
  onChanged: () => void;
}) {
  const [open, setOpen] = useState<string | null>(null);
  const names = useMemo(() => new Map(leashes.map((l) => [l.agent.id, l.agent])), [leashes]);

  if (ledger.length === 0) {
    return <p className="empty">The ledger is empty. Press <b>Run demo</b> to send Larder bot shopping.</p>;
  }
  return (
    <div className="card">
      <div className="table-wrap">
        <table className="table ledger">
          <thead>
            <tr>
              <th scope="col" className="hide-sm">When</th>
              <th scope="col" className="hide-sm">Agent</th>
              <th scope="col">Item</th>
              <th scope="col" className="hide-sm">Shop</th>
              <th scope="col" className="r">Amount</th>
              <th scope="col">Decision</th>
              <th scope="col" className="hide-sm">Money</th>
              <th scope="col"><span className="sr-only">Details</span></th>
            </tr>
          </thead>
          <tbody>
            {ledger.map((e) => {
              const id = e.id ?? e.request.id;
              const isOpen = open === id;
              const agent = names.get(e.request.agentId);
              return (
                <Fragment key={id}>
                  <tr className={`ledger-row ${isOpen ? "is-open" : ""} ${id === latestId ? "is-new" : ""}`}
                      onClick={() => setOpen(isOpen ? null : id)}>
                    <td className="num dim hide-sm">{dateTimeUk(e.request.requestedAt)}</td>
                    <td className="hide-sm"><i className={`id-mark id-${agent?.kind ?? "other"}`} />{agent?.name ?? e.request.agentId}</td>
                    <td className="item">
                      {e.request.description}
                      <span className="show-sm xs dim"><i className={`id-mark id-${agent?.kind ?? "other"}`} />{agent?.name ?? e.request.agentId}</span>
                    </td>
                    <td className="hide-sm">{e.request.merchant}</td>
                    <td className="num r">{money(e.request.amountMinor, e.request.currency)}</td>
                    <td><OutcomePill outcome={e.decision.outcome} /></td>
                    <td className="hide-sm">{e.status && <StatusTag status={e.status} />}</td>
                    <td className="r">
                      <button type="button" className="icon-btn row-toggle" aria-expanded={isOpen}
                              aria-label={`${isOpen ? "Hide" : "Show"} reasons for ${e.request.description}`}
                              onClick={(ev) => { ev.stopPropagation(); setOpen(isOpen ? null : id); }}>
                        <ChevronIcon />
                      </button>
                    </td>
                  </tr>
                  {isOpen && (
                    <tr className="reason-row">
                      <td colSpan={8}>
                        <ReasonCard entry={e} />
                        <EntryActions entry={e} onChanged={onChanged} />
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/** What the human can still do with an entry: confirm in PayPal, or capture once confirmed. */
export function EntryActions({ entry, onChanged }: { entry: LedgerEntry; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (entry.status !== "AWAITING_PAYER") return null;
  const capture = () => {
    setBusy(true);
    setError(null);
    captureEntry(entry.id ?? entry.request.id)
      .then(onChanged)
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };
  return (
    <div className="entry-actions">
      {entry.approveUrl && (
        <a className="btn btn-pp" href={entry.approveUrl} target="_blank" rel="noreferrer">Confirm in PayPal sandbox</a>
      )}
      <button type="button" className="btn" disabled={busy} onClick={capture}>I've confirmed: capture</button>
      {error && <p className="form-error" role="alert">{error}</p>}
    </div>
  );
}
