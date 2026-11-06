import { useEffect, useMemo, useState } from "react";
import type { DemoLedger, Health } from "./types";
import { fetchDemoLedger, fetchHealth } from "./api";
import { SAMPLE_LEDGER } from "./lib/sample";
import { useRoute } from "./hooks/useRoute";
import { TopBar } from "./components/layout/TopBar";
import { Footer } from "./components/layout/Footer";
import { DemoBanner } from "./components/layout/DemoBanner";
import { Tape, type TapeItem } from "./components/landing/Tape";
import { Hero } from "./components/landing/Hero";
import { LedgerPreview } from "./components/ledger/LedgerPreview";
import { HowItWorks } from "./components/landing/HowItWorks";
import { UseCases } from "./components/landing/UseCases";
import { Dashboard } from "./components/app/Dashboard";

// Extra tape entries from the other demo leashes, so the feed shows more than one agent.
const OTHER_WALKS: TapeItem[] = [
  { agent: "Office bot", merchant: "Viking", amountMinor: 2399, outcome: "APPROVE" },
  { agent: "Travel bot", merchant: "Trainline", amountMinor: 8650, outcome: "APPROVE" },
  { agent: "Office bot", merchant: "Viking", amountMinor: 6400, outcome: "TUG" },
  { agent: "Travel bot", merchant: "Premier Inn", amountMinor: 18900, outcome: "TUG" },
  { agent: "Gift bot", merchant: "Tesc0", amountMinor: 3000, outcome: "BLOCK" },
];

export default function App() {
  const route = useRoute();
  const [health, setHealth] = useState<Health | null>(null);
  const [ledger, setLedger] = useState<{ data: DemoLedger; live: boolean }>({ data: SAMPLE_LEDGER, live: false });

  useEffect(() => {
    fetchHealth().then(setHealth);
    fetchDemoLedger().then(setLedger);
  }, []);

  const tape = useMemo<TapeItem[]>(() => {
    const mine = ledger.data.entries.map((e) => ({
      agent: "Larder bot",
      merchant: e.request.merchant,
      amountMinor: e.request.amountMinor,
      outcome: e.decision.outcome,
    }));
    return [mine[0], OTHER_WALKS[0], mine[1], OTHER_WALKS[1], OTHER_WALKS[2], mine[2], OTHER_WALKS[3], OTHER_WALKS[4]].filter(Boolean);
  }, [ledger]);

  if (route.page === "app") {
    return (
      <>
        <DemoBanner health={health} />
        <TopBar health={health} inApp />
        <Dashboard tab={route.tab} health={health} />
        <Footer />
      </>
    );
  }

  return (
    <>
      <DemoBanner health={health} />
      <TopBar health={health} />
      <Tape items={tape} />
      <main>
        <Hero canRun={health !== null} />
        <LedgerPreview data={ledger.data} live={ledger.live} />
        <HowItWorks />
        <UseCases />
      </main>
      <Footer />
    </>
  );
}
