import { lazy, Suspense, useState } from "react";
import type { Tab } from "../../hooks/useRoute";
import { tabHref } from "../../hooks/useRoute";
import { useLeashData } from "../../hooks/useLeashData";
import { resetDemo, runDemo } from "../../api";
import { LeashCards } from "./LeashCards";
import { TugList } from "./TugList";
// AG Grid is the biggest dependency by far: load it only when the ledger tab opens.
const LedgerGrid = lazy(() => import("./LedgerGrid").then((m) => ({ default: m.LedgerGrid })));
import { NewLeash } from "./NewLeash";
import { WalletCard } from "./WalletCard";

const TAB_LABEL: Record<Tab, string> = { leashes: "Leashes", tugs: "Tugs", ledger: "Ledger", new: "New leash" };

/** The signed-in view: leashes, tugs waiting for you, the ledger, and the mandate composer. Live over SSE. */
export function Dashboard({ tab }: { tab: Tab }) {
  const data = useLeashData();
  const [demoBusy, setDemoBusy] = useState(false);
  const [demoMsg, setDemoMsg] = useState<string | null>(null);

  const demo = (fn: () => Promise<unknown>, msg: string) => () => {
    setDemoBusy(true);
    setDemoMsg(null);
    fn()
      .then(() => { setDemoMsg(msg); data.refresh(); })
      .catch((e: Error) => setDemoMsg(e.message))
      .finally(() => setDemoBusy(false));
  };

  const counts: Partial<Record<Tab, number>> = { tugs: data.tugs.length };

  return (
    <main className="container app">
      <div className="app-head">
        <div>
          <h1>Your agents</h1>
          <p className="muted small">
            <span className={`stream-dot is-${data.stream}`} aria-hidden="true" />
            {data.stream === "live" ? "Live: decisions appear as agents ask." : data.stream === "connecting" ? "Connecting…" : "Not live: refresh to update."}
          </p>
        </div>
        <div className="app-actions">
          <button type="button" className="btn" disabled={demoBusy} onClick={demo(resetDemo, "Demo reset.")}>Reset</button>
          <button type="button" className="btn btn-primary" disabled={demoBusy}
                  onClick={demo(runDemo, "Larder bot is shopping. Watch the ledger.")}>Run demo</button>
        </div>
      </div>
      {demoMsg && <p className="notice ok app-msg" role="status">{demoMsg}</p>}
      {data.error && <p className="notice" role="alert">Couldn't reach the API: {data.error}</p>}

      <nav className="tabs" aria-label="Dashboard">
        {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
          <a key={t} href={tabHref(t)} className={`tab ${t === tab ? "is-on" : ""}`} aria-current={t === tab ? "page" : undefined}>
            {TAB_LABEL[t]}
            {counts[t] ? <span className="tab-count num">{counts[t]}</span> : null}
          </a>
        ))}
      </nav>

      <section className="tab-panel" aria-label={TAB_LABEL[tab]}>
        {!data.loaded ? (
          <p className="empty">Loading…</p>
        ) : tab === "leashes" ? (
          <div className="stack">
            <WalletCard />
            <LeashCards leashes={data.leashes} onChanged={data.refresh} />
          </div>
        ) : tab === "tugs" ? (
          <TugList tugs={data.tugs} leashes={data.leashes} onChanged={data.refresh} />
        ) : tab === "ledger" ? (
          <Suspense fallback={<p className="empty">Loading the ledger…</p>}>
            <LedgerGrid ledger={data.ledger} leashes={data.leashes} latestId={data.latestId} onChanged={data.refresh} />
          </Suspense>
        ) : (
          <NewLeash onCreated={data.refresh} />
        )}
      </section>
    </main>
  );
}
