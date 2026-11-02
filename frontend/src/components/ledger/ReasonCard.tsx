import type { LedgerEntry } from "../../types";
import { reasonLabel, riskFirst, STATUS_LABEL } from "../../lib/status";

/**
 * Why Leash decided what it did, in plain English: the rules that fired, the risk signals, the AI's sentence (when it
 * ran) and what happened in PayPal. Used by the landing preview and the dashboard ledger.
 */
export function ReasonCard({ entry }: { entry: LedgerEntry }) {
  const { request, decision } = entry;
  const reasons = riskFirst(decision.reasons);
  return (
    <div className={`reason-card out-${decision.outcome}`}>
      <div className="reason-meta">
        {request.task && <p><span className="dim">Task given</span> {request.task}</p>}
        {request.reason && <p><span className="dim">Agent's reason</span> {request.reason}</p>}
        {request.sourceUrl && <p><span className="dim">Found on</span> <code>{request.sourceUrl}</code></p>}
      </div>
      {reasons.length === 0 ? (
        <p className="reason-ok">Inside every rule on the leash, so Leash paid it through PayPal without asking.</p>
      ) : (
        <ul className="reasons">
          {reasons.map((r) => (
            <li key={r.code}>
              <code className="reason-code">{reasonLabel(r.code)}</code>
              <span>{r.message}</span>
            </li>
          ))}
        </ul>
      )}
      {entry.riskExplanation && (
        <p className="reason-ai"><span className="label">AI risk check</span> {entry.riskExplanation}</p>
      )}
      {entry.status && (
        <dl className="reason-pay">
          <div><dt>Status</dt><dd>{STATUS_LABEL[entry.status]}</dd></div>
          {entry.statusDetail && <div className="wide"><dt>Detail</dt><dd>{entry.statusDetail}</dd></div>}
          {entry.paypalOrderId && <div><dt>PayPal order</dt><dd className="num pp">{entry.paypalOrderId}</dd></div>}
          {entry.paypalCaptureId && <div><dt>PayPal capture</dt><dd className="num pp">{entry.paypalCaptureId}</dd></div>}
          {entry.paypalRefundId && <div><dt>PayPal refund</dt><dd className="num pp">{entry.paypalRefundId}</dd></div>}
        </dl>
      )}
    </div>
  );
}
