import { useEffect, useRef, type ReactNode } from "react";
import type { Agent, LedgerEntry } from "../../types";
import { money } from "../../lib/format";
import { dateTimeUk } from "../../lib/status";
import { OutcomePill } from "../ui/OutcomePill";
import { ReasonCard } from "./ReasonCard";

/** The reason card for one ledger row, as a side panel (a bottom sheet on phones). Esc or the backdrop closes it. */
export function ReasonDrawer({ entry, agent, onClose, children }: {
  entry: LedgerEntry;
  agent?: Agent;
  onClose: () => void;
  children?: ReactNode;
}) {
  const close = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    close.current?.focus();
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    window.addEventListener("keydown", onKey);
    document.body.classList.add("has-drawer");
    return () => {
      window.removeEventListener("keydown", onKey);
      document.body.classList.remove("has-drawer");
      before?.focus?.();
    };
  }, [onClose]);

  const r = entry.request;
  return (
    <div className="drawer-wrap" role="presentation">
      <button type="button" className="drawer-backdrop" aria-label="Close" tabIndex={-1} onClick={onClose} />
      <aside className={`drawer out-${entry.decision.outcome}`} role="dialog" aria-modal="true" aria-labelledby="drawer-title">
        <header className="drawer-head">
          <div className="drawer-title">
            <p className="xs dim"><i className={`id-mark id-${agent?.kind ?? "other"}`} />{agent?.name ?? r.agentId} · <span className="num">{dateTimeUk(r.requestedAt)}</span></p>
            <h2 id="drawer-title">{r.description}</h2>
            <p className="drawer-sub"><span className="num drawer-amount">{money(r.amountMinor, r.currency)}</span> at {r.merchant} <OutcomePill outcome={entry.decision.outcome} /></p>
          </div>
          <button ref={close} type="button" className="icon-btn" aria-label="Close reasons" onClick={onClose}>
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M6 6l12 12M18 6L6 18" /></svg>
          </button>
        </header>
        <div className="drawer-body">
          <ReasonCard entry={entry} />
          {children}
        </div>
      </aside>
    </div>
  );
}
