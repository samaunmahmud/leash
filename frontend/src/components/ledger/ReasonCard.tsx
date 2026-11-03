import type { LedgerEntry, Outcome } from "../../types";
import { decisionChain, FORCES, headline, laneOf, rankReasons, REASON_TITLE } from "../../lib/decision";
import { STATUS_LABEL } from "../../lib/status";
import { CheckIcon, HandIcon, StopIcon } from "../ui/Icons";

const WORD: Record<Outcome, string> = { APPROVE: "Approve", TUG: "Tug", BLOCK: "Block" };
const FINAL_WORD: Record<Outcome, string> = { APPROVE: "Approved", TUG: "Tugged", BLOCK: "Blocked" };
const ICON: Record<Outcome, JSX.Element> = { APPROVE: <CheckIcon />, TUG: <HandIcon />, BLOCK: <StopIcon /> };
const LEVEL_WORD = { HIGH: "High risk", MEDIUM: "Medium risk", LOW: "Low risk", NONE: "Didn't answer" } as const;
const LANE_WORD = { rules: "Leash rule", risk: "Risk check", ai: "AI" } as const;
const EVIDENCE_LABEL: Record<string, string> = {
  PROMPT_INJECTION: "What the page hid from you",
  LOOKALIKE_MERCHANT: "This shop vs the real one",
};

/**
 * Why Leash decided what it did, in plain English. Leads with the most important reason (an attack first), shows each
 * check's own verdict so it's obvious the AI can only tighten a decision, then the context the agent gave and what
 * happened in PayPal. Every string from the agent or the page is rendered as text, never HTML.
 */
export function ReasonCard({ entry }: { entry: LedgerEntry }) {
  const { request: r, decision } = entry;
  const chain = decisionChain(entry);
  const ranked = rankReasons(decision.reasons);
  const lead = decision.outcome !== "APPROVE" ? ranked[0] : undefined;
  const rest = lead ? ranked.slice(1) : ranked;

  return (
    <div className={`rc out-${decision.outcome}`}>
      <p className="rc-headline">{headline(entry)}</p>

      <ol className="rc-chain" aria-label="How Leash decided">
        <Step label="Rules" outcome={chain.rules} note={count(entry, "rules")} />
        <Step label="Risk checks" outcome={chain.risk} note={chain.risk ? count(entry, "risk") : "Nothing found"} />
        <Step label="AI check" outcome={chain.ai.outcome}
              note={chain.ai.ran ? LEVEL_WORD[chain.ai.level] : "Didn't answer"} />
        <li className={`rc-step rc-final out-${chain.final}`}>
          <span className="rc-step-label">Final</span>
          <b>{ICON[chain.final]}{FINAL_WORD[chain.final]}</b>
          <span className="rc-step-note">The strictest of the three</span>
        </li>
      </ol>
      <p className="rc-promise xs dim">Each check can only make the decision stricter. The AI can't approve anything.</p>

      {lead && (
        <section className={`rc-lead out-${decision.outcome}`} aria-label="Main reason">
          <p className="label">{decision.outcome === "BLOCK" ? "Why it was blocked" : "Why it needs you"} · {LANE_WORD[laneOf(lead.code)]}</p>
          <h4>{REASON_TITLE[lead.code] ?? lead.code}</h4>
          <p>{lead.message}</p>
          {lead.evidence && (
            <blockquote className="rc-evidence">
              <span className="label">{EVIDENCE_LABEL[lead.code] ?? "What triggered it"}</span>
              <span className="rc-evidence-text">“{lead.evidence}”</span>
            </blockquote>
          )}
        </section>
      )}

      {decision.reasons.length === 0 ? (
        <p className="rc-ok">Inside every rule on the leash, so Leash paid it through PayPal without asking.</p>
      ) : rest.length > 0 && (
        <div className="rc-more">
          <p className="label">{lead ? "Also" : "Rules that fired"}</p>
          <ul className="rc-reasons">
            {rest.map((x) => (
              <li key={x.code} className={`out-${FORCES[x.code] ?? "TUG"}`}>
                <span className="rc-reason-title">{REASON_TITLE[x.code] ?? x.code}<span className="rc-lane">{LANE_WORD[laneOf(x.code)]}</span></span>
                <span className="rc-reason-msg">{x.message}</span>
                {x.evidence && <span className="rc-evidence-inline">“{x.evidence}”</span>}
              </li>
            ))}
          </ul>
        </div>
      )}

      <section className="rc-ai" aria-label="AI risk check">
        <p className="rc-ai-head">
          <span className="ai-tag">AI</span>
          <span className="label">Risk check</span>
          {chain.ai.ran
            ? <span className={`status-tag out-${chain.ai.level === "HIGH" ? "BLOCK" : chain.ai.level === "MEDIUM" ? "TUG" : "APPROVE"}`}>{LEVEL_WORD[chain.ai.level]}</span>
            : <span className="dim small">Didn't answer</span>}
        </p>
        <p className="small">
          {entry.riskExplanation
            ?? (chain.ai.ran ? "The AI flagged this without a comment."
              : "No AI verdict for this one (no model configured, or it didn't answer in time). Leash's rules and rule-based risk checks decided on their own.")}
        </p>
      </section>

      <dl className="rc-context">
        <div><dt>Asked to</dt><dd>{r.task ?? <span className="dim">The agent didn't say</span>}</dd></div>
        <div><dt>The agent's reason</dt><dd>{r.reason ? <>“{r.reason}”</> : <span className="dim">None given</span>}</dd></div>
        <div><dt>Found on</dt><dd>{r.sourceUrl ? <code className="num">{r.sourceUrl}</code> : <span className="dim">No page given</span>}</dd></div>
      </dl>

      {entry.status && (
        <dl className="reason-pay">
          <div><dt>Money</dt><dd>{STATUS_LABEL[entry.status]}</dd></div>
          {entry.paypalOrderId && <div><dt>PayPal order</dt><dd className="num pp">{entry.paypalOrderId}</dd></div>}
          {entry.paypalCaptureId && <div><dt>PayPal capture</dt><dd className="num pp">{entry.paypalCaptureId}</dd></div>}
          {entry.paypalRefundId && <div><dt>PayPal refund</dt><dd className="num pp">{entry.paypalRefundId}</dd></div>}
          {entry.statusDetail && <div className="wide"><dt>Detail</dt><dd>{entry.statusDetail}</dd></div>}
        </dl>
      )}
    </div>
  );
}

function Step({ label, outcome, note }: { label: string; outcome: Outcome | null; note: string }) {
  return (
    <li className={`rc-step ${outcome ? `out-${outcome}` : "is-quiet"}`}>
      <span className="rc-step-label">{label}</span>
      <b>{outcome ? <>{ICON[outcome]}{WORD[outcome]}</> : "—"}</b>
      <span className="rc-step-note">{note}</span>
    </li>
  );
}

function count(e: LedgerEntry, lane: "rules" | "risk"): string {
  const n = e.decision.reasons.filter((r) => laneOf(r.code) === lane).length;
  return n === 0 ? "All passed" : n === 1 ? "1 fired" : `${n} fired`;
}
