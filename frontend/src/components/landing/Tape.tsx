import type { Outcome } from "../../types";
import { money, OUTCOME_LABEL } from "../../lib/format";

export interface TapeItem {
  agent: string;
  merchant: string;
  amountMinor: number;
  outcome: Outcome;
}

/** A running feed of agent requests, like Zenith's market tape. Duplicated once so the loop is seamless. */
export function Tape({ items }: { items: TapeItem[] }) {
  if (items.length === 0) return null;
  const row = items.map((it, i) => (
    <span className="tape-item" key={i}>
      <b>{it.agent}</b>
      <span className="dim">{it.merchant}</span>
      <span className="num">{money(it.amountMinor, "GBP")}</span>
      <span className={`pill out-${it.outcome}`}>{OUTCOME_LABEL[it.outcome]}</span>
    </span>
  ));
  return (
    <div className="tape" aria-label="Recent agent requests">
      <div className="tape-head">Today's walks</div>
      <div className="tape-track">
        <div className="tape-run">{row}</div>
        <div className="tape-run" aria-hidden="true">{row}</div>
      </div>
    </div>
  );
}
