import { useEffect, useState } from "react";
import type { WalletView } from "../../types";
import { completeWallet, fetchWallet, forgetWallet, startWallet } from "../../api";
import { walletState } from "../../lib/wallet";

/**
 * "Pay while you're away": save the owner's PayPal once (Vault), then approved purchases are paid with no popup.
 * Saving PayPal never widens a leash: the engine still decides every purchase.
 */
export function WalletCard() {
  const [w, setW] = useState<WalletView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () => fetchWallet().then(setW).catch(() => setW(null));
  useEffect(() => {
    load();
    // Coming back from the PayPal tab: pick up the new status.
    const onFocus = () => load();
    window.addEventListener("focus", onFocus);
    return () => window.removeEventListener("focus", onFocus);
  }, []);

  const act = (fn: () => Promise<WalletView>, open = false) => () => {
    // Open the tab inside the click so pop-up blockers allow it, then point it at PayPal.
    const tab = open ? window.open("about:blank", "_blank") : null;
    setBusy(true);
    setError(null);
    fn()
      .then((v) => {
        setW(v);
        if (tab && v.approveUrl) tab.location.href = v.approveUrl;
        else tab?.close();
      })
      .catch((e: Error) => { tab?.close(); setError(e.message); })
      .finally(() => setBusy(false));
  };

  if (!w) return null;
  const { label, actions } = walletState(w);

  return (
    <section className={`card wallet is-${w.status.toLowerCase()}`} aria-labelledby="wallet-title">
      <div className="wallet-main">
        <div className="wallet-icon" aria-hidden="true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="6" width="18" height="13" rx="2.5" /><path d="M3 10h18M16 14.5h2" />
          </svg>
        </div>
        <div className="wallet-text">
          <h3 id="wallet-title">
            Pay while you're away
            <span className="status-tag wallet-state">{label}</span>
          </h3>
          <p className="small muted">
            {w.status === "ACTIVE" && <>Approved purchases are paid from <span className="pp num">{w.hint ?? "your PayPal"}</span> with no popup. Tugs still ask you first.</>}
            {w.status === "PENDING" && <>Approve once in the PayPal sandbox with your Personal account, then come back here.</>}
            {w.status === "NONE" && <>Save your PayPal once and approved purchases are paid without a popup. Each purchase still has to pass its leash.</>}
          </p>
          {!w.paypalConfigured && <p className="xs dim">PayPal isn't configured on the server.</p>}
          {error && <p className="form-error" role="alert">{error}</p>}
        </div>
      </div>
      <div className="wallet-actions">
        {actions.includes("save") && (
          <button type="button" className="btn btn-primary" disabled={busy} onClick={act(startWallet, true)}>Save PayPal</button>
        )}
        {actions.includes("approve") && w.approveUrl && (
          <a className="btn btn-pp" href={w.approveUrl} target="_blank" rel="noopener noreferrer">Approve in PayPal</a>
        )}
        {actions.includes("complete") && (
          <button type="button" className="btn btn-primary" disabled={busy} onClick={act(completeWallet)}>I've approved it</button>
        )}
        {actions.includes("cancel") && (
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={act(forgetWallet)}>Cancel</button>
        )}
        {actions.includes("forget") && (
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={act(forgetWallet)}>Forget PayPal</button>
        )}
      </div>
    </section>
  );
}
