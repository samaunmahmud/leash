import { useEffect, useRef, useState, type FormEvent } from "react";
import type { Health } from "../../types";
import { OWNER_NEEDED, unlockOwner } from "../../api";
import { onOwnerChange, ownerPassphrase, setOwnerPassphrase } from "../../lib/owner";

/**
 * "Owner mode": the passphrase that unlocks agent keys, saved PayPal and leash edits. The demo leashes work without
 * it. Shown only when the server gates owner actions.
 */
export function OwnerUnlock({ health }: { health: Health | null }) {
  const [unlocked, setUnlocked] = useState(() => !!ownerPassphrase());
  const [open, setOpen] = useState(false);
  const [text, setText] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const [asked, setAsked] = useState(false);
  const formRef = useRef<HTMLFormElement>(null);

  useEffect(() => onOwnerChange(() => setUnlocked(!!ownerPassphrase())), []);
  useEffect(() => {
    const open = () => { setOpen(true); setAsked(true); setErr(null); };
    window.addEventListener(OWNER_NEEDED, open);
    return () => window.removeEventListener(OWNER_NEEDED, open);
  }, []);
  useEffect(() => {
    if (open) formRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
  }, [open]);

  if (!health?.ownerGated) return null;
  if (!health.ownerEnabled) {
    return <span className="owner-state muted small" title="No owner passphrase is set on this server.">Owner actions off</span>;
  }
  if (unlocked) {
    return (
      <button type="button" className="btn btn-ghost" onClick={() => setOwnerPassphrase(null)} title="Lock owner actions again">
        Owner mode on · Lock
      </button>
    );
  }
  if (!open) {
    return <button type="button" className="btn" onClick={() => { setOpen(true); setErr(null); }}>Owner mode</button>;
  }

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!text) return;
    setBusy(true);
    setErr(null);
    unlockOwner(text)
      .then(() => { setOpen(false); setText(""); setAsked(false); })
      .catch((e: Error) => setErr(e.message))
      .finally(() => setBusy(false));
  };

  return (
    <form className="owner-form" onSubmit={submit} ref={formRef}>
      {asked && <p className="owner-hint small">That needs owner mode. Enter the passphrase, then try again.</p>}
      <label htmlFor="owner-pass" className="sr-only">Owner passphrase</label>
      <input id="owner-pass" type="password" value={text} onChange={(e) => setText(e.target.value)}
             placeholder="Owner passphrase" autoComplete="current-password" autoFocus
             onKeyDown={(e) => { if (e.key === "Escape") { setOpen(false); setAsked(false); } }} />
      <button type="submit" className="btn btn-primary" disabled={busy || !text}>Unlock</button>
      <button type="button" className="btn btn-ghost" onClick={() => { setOpen(false); setAsked(false); }}>Cancel</button>
      {err && <p className="owner-err small" role="alert">{err}</p>}
    </form>
  );
}
