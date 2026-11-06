import { useState } from "react";
import type { AgentKey, LeashCard } from "../../types";
import { money, usedPercent } from "../../lib/format";
import { hhmm, PERIOD_WORD, titleCase } from "../../lib/status";
import { issueAgentKey, releash, snapBack } from "../../api";
import { claudeDesktopConfig } from "../../lib/mcpConfig";

/** One card per leash: how much of the length is used, the rules, and the snap-back switch. */
export function LeashCards({ leashes, onChanged }: { leashes: LeashCard[]; onChanged: () => void }) {
  if (leashes.length === 0) {
    return <p className="empty">No leashes yet. Write one in plain English under <a href="#/app/new">New leash</a>.</p>;
  }
  return (
    <div className="leash-grid">
      {leashes.map((l) => (
        <LeashCardView key={l.agent.id} leash={l} onChanged={onChanged} />
      ))}
    </div>
  );
}

function LeashCardView({ leash, onChanged }: { leash: LeashCard; onChanged: () => void }) {
  const { agent, mandate: m, usage } = leash;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [key, setKey] = useState<AgentKey | null>(null);
  const spent = usage?.spentMinor ?? 0;
  const pending = usage?.pendingMinor ?? 0;
  const left = Math.max(0, m.periodLimitMinor - spent - pending);

  const act = (fn: (id: string) => Promise<unknown>) => () => {
    setBusy(true);
    setError(null);
    fn(agent.id)
      .then(onChanged)
      .catch((e: Error) => setError(e.message))
      .finally(() => setBusy(false));
  };

  const rules: string[] = [];
  if (m.allowedMerchants.length) rules.push(`Only ${m.allowedMerchants.map(titleCase).join(", ")}`);
  if (m.allowedCategories?.length) rules.push(titleCase(m.allowedCategories.join(", ")));
  if (m.blockedCategories.length) rules.push(`Never ${m.blockedCategories.join(", ")}`);
  if (m.maxPurchasesPerHour) rules.push(`Max ${m.maxPurchasesPerHour} an hour`);
  if (m.quietStart && m.quietEnd) rules.push(`Always asks ${hhmm(m.quietStart)}–${hhmm(m.quietEnd)}`);

  return (
    <article className={`card leash-card id-${agent.kind} ${agent.revoked ? "is-snapped" : ""}`}>
      <div className="card-head">
        <div>
          <h3><i className="id-mark" />{agent.name}</h3>
          <p className="sub">{m.name}</p>
        </div>
        {agent.revoked ? (
          <span className="pill out-BLOCK">Snapped back</span>
        ) : (
          <span className="pill out-APPROVE">On the leash</span>
        )}
      </div>
      <div className="leash-body">
        <div className="length" aria-label={`${m.name}: length used`}>
          <div className="length-head">
            <span className="label">Length</span>
            <span><span className="num">{money(m.periodLimitMinor, m.currency)}</span> <span className="dim">{PERIOD_WORD[m.period]}</span></span>
          </div>
          <div className="length-bar">
            <span className="length-paid" style={{ width: `${usedPercent(spent, m.periodLimitMinor)}%` }} />
            <span className="length-pending" style={{ width: `${usedPercent(pending, m.periodLimitMinor)}%` }} />
          </div>
          <p className="xs dim">
            <span className="num">{money(spent, m.currency)}</span> used · <span className="num">{money(pending, m.currency)}</span> waiting ·{" "}
            <span className="num">{money(left, m.currency)}</span> left
          </p>
        </div>
        <dl className="facts">
          <div><dt>Slack</dt><dd className="num">{money(m.autoApproveUpToMinor, m.currency)}</dd></div>
          <div><dt>Per purchase</dt><dd className="num">{m.perPurchaseMaxMinor ? money(m.perPurchaseMaxMinor, m.currency) : "No cap"}</dd></div>
        </dl>
        {rules.length > 0 && (
          <ul className="rule-chips">
            {rules.map((r) => <li key={r}>{r}</li>)}
          </ul>
        )}
        {leash.sourceText && <p className="source-text">“{leash.sourceText}”</p>}
        {error && <p className="form-error" role="alert">{error}</p>}
        {key && <KeyPanel k={key} onClose={() => { setKey(null); onChanged(); }} />}
      </div>
      <div className="card-foot">
        {agent.revoked ? (
          <button type="button" className="btn" disabled={busy} onClick={act(releash)}>Re-leash</button>
        ) : (
          <button type="button" className="btn btn-danger" disabled={busy} onClick={act(snapBack)}
                  title="Revoke this agent now. Every request is refused until you re-leash it.">
            Snap back
          </button>
        )}
        <button type="button" className="btn btn-ghost" disabled={busy}
                title={leash.keyPrefix ? `Live key starts ${leash.keyPrefix}. A new key replaces it.` : "Connect an agent over MCP"}
                onClick={() => {
                  if (leash.keyPrefix && !window.confirm("Replace this agent's key? The old key stops working at once.")) return;
                  setBusy(true);
                  setError(null);
                  issueAgentKey(agent.id).then(setKey).catch((e: Error) => setError(e.message)).finally(() => setBusy(false));
                }}>
          {leash.keyPrefix ? "New key" : "Agent key"}
        </button>
        <a className="btn btn-ghost" href={`#/app/ledger?agent=${encodeURIComponent(agent.id)}`}>Ledger</a>
      </div>
    </article>
  );
}

/** The key, once. Copy it now: Leash only keeps its hash. */
function KeyPanel({ k, onClose }: { k: AgentKey; onClose: () => void }) {
  const [copied, setCopied] = useState<string | null>(null);
  const config = claudeDesktopConfig(k.mcpUrl, k.key);
  const copy = (what: string, text: string) => () =>
    navigator.clipboard?.writeText(text).then(() => setCopied(what)).catch(() => setCopied(null));
  return (
    <div className="key-panel" role="region" aria-label="Agent key">
      <p className="label">Agent key · shown once</p>
      <code className="num key-value">{k.key}</code>
      <p className="xs dim">MCP URL <span className="num">{k.mcpUrl}</span>. Send it as <span className="num">Authorization: Bearer …</span></p>
      <details>
        <summary className="small">MCP client config (JSON, via mcp-remote)</summary>
        <pre className="num key-config">{config}</pre>
      </details>
      <div className="entry-actions">
        <button type="button" className="btn" onClick={copy("key", k.key)}>{copied === "key" ? "Copied" : "Copy key"}</button>
        <button type="button" className="btn" onClick={copy("config", config)}>{copied === "config" ? "Copied" : "Copy config"}</button>
        <button type="button" className="btn btn-ghost" onClick={onClose}>Done</button>
      </div>
    </div>
  );
}
