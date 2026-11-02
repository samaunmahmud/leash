import type { EntryStatus, Outcome } from "../../types";
import { OUTCOME_LABEL } from "../../lib/format";
import { STATUS_LABEL, statusTone } from "../../lib/status";
import { CheckIcon, HandIcon, StopIcon } from "./Icons";

const ICON: Record<Outcome, JSX.Element> = { APPROVE: <CheckIcon />, TUG: <HandIcon />, BLOCK: <StopIcon /> };

/** What Leash decided: colour + icon + word, never colour alone. */
export function OutcomePill({ outcome }: { outcome: Outcome }) {
  return (
    <span className={`pill out-${outcome}`}>
      {ICON[outcome]}
      {OUTCOME_LABEL[outcome]}
    </span>
  );
}

/** What happened to the money afterwards. Quieter than the outcome pill. */
export function StatusTag({ status }: { status: EntryStatus }) {
  const tone = statusTone(status);
  return <span className={`status-tag ${tone === "NEUTRAL" ? "" : `out-${tone}`}`}>{STATUS_LABEL[status]}</span>;
}
