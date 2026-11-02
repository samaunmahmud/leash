import { Fragment, useState } from "react";
import type { DemoLedger } from "../../types";
import { money, usedPercent } from "../../lib/format";
import { timeUk as time } from "../../lib/status";
import { ChevronIcon } from "../ui/Icons";
import { OutcomePill } from "../ui/OutcomePill";
import { ReasonCard } from "./ReasonCard";

/**
 * The landing page's ledger example. The dashboard's live ledger is components/app/LedgerGrid.
 */
export function LedgerPreview({ data, live }: { data: DemoLedger; live: boolean }) {
  const [open, setOpen] = useState<string | null>(data.entries.at(-1)?.request.id ?? null);
  const m = data.mandate;
  const paid = data.entries.filter((e) => e.decision.outcome === "APPROVE").reduce((s, e) => s + e.request.amountMinor, 0);
  const pending = data.entries.filter((e) => e.decision.outcome === "TUG").reduce((s, e) => s + e.request.amountMinor, 0);

  return (
    <section id="ledger" className="container section">
      <div className="section-head">
        <h2>Every attempt, on the record</h2>
        <p className="muted">The grocery agent's afternoon: one paid, one waiting for you, one stopped. Select a row to see why.</p>
      </div>

      <div className="card">
        <div className="card-head">
          <div>
            <h3><i className="id-mark id-grocery" />Larder bot <span className="dim">· {m.name}</span></h3>
            <p className="sub">{money(m.periodLimitMinor, m.currency)} a week, auto-pay up to {money(m.autoApproveUpToMinor, m.currency)}</p>
          </div>
          <div className="length" aria-label="Weekly length used">
            <div className="length-bar">
              <span className="length-paid" style={{ width: `${usedPercent(paid, m.periodLimitMinor)}%` }} />
              <span className="length-pending" style={{ width: `${usedPercent(pending, m.periodLimitMinor)}%` }} />
            </div>
            <p className="xs dim">
              <span className="num">{money(paid, m.currency)}</span> paid · <span className="num">{money(pending, m.currency)}</span> waiting ·{" "}
              <span className="num">{money(Math.max(0, m.periodLimitMinor - paid - pending), m.currency)}</span> left
            </p>
          </div>
        </div>

        <div className="table-wrap">
          <table className="table ledger">
            <thead>
              <tr>
                <th scope="col" className="hide-sm">Time</th>
                <th scope="col">Item</th>
                <th scope="col" className="hide-sm">Shop</th>
                <th scope="col" className="r">Amount</th>
                <th scope="col">Outcome</th>
                <th scope="col"><span className="sr-only">Details</span></th>
              </tr>
            </thead>
            <tbody>
              {data.entries.map((e) => {
                const isOpen = open === e.request.id;
                return (
                  <Fragment key={e.request.id}>
                    <tr className={`ledger-row ${isOpen ? "is-open" : ""}`} onClick={() => setOpen(isOpen ? null : e.request.id)}>
                      <td className="num dim hide-sm">{time(e.request.requestedAt)}</td>
                      <td className="item">{e.request.description}</td>
                      <td className="hide-sm">{e.request.merchant}</td>
                      <td className="num r">{money(e.request.amountMinor, e.request.currency)}</td>
                      <td>
                        <OutcomePill outcome={e.decision.outcome} />
                      </td>
                      <td className="r">
                        <button type="button" className="icon-btn row-toggle" aria-expanded={isOpen}
                                aria-label={`${isOpen ? "Hide" : "Show"} reasons for ${e.request.description}`}
                                onClick={(ev) => { ev.stopPropagation(); setOpen(isOpen ? null : e.request.id); }}>
                          <ChevronIcon />
                        </button>
                      </td>
                    </tr>
                    {isOpen && (
                      <tr className="reason-row">
                        <td colSpan={6}>
                          <ReasonCard entry={e} />
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
      {!live && (
        <p className="xs dim offline-note">
          Showing the scenario as the engine decides it. Start the API (<code>mvn spring-boot:run</code>) to see it computed live.
        </p>
      )}
    </section>
  );
}
