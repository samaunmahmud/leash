import { useEffect, useState } from "react";
import type { Outcome } from "../../types";
import { money, OUTCOME_LABEL } from "../../lib/format";
import { BrandMark } from "../layout/Brand";

// SVG coordinates (viewBox 560 × 420). Node cards are positioned with the same numbers as percentages.
const W = 560;
const H = 420;
const AGENT_Y = [76, 210, 344];
const OUT_Y: Record<Outcome, number> = { APPROVE: 76, TUG: 210, BLOCK: 344 };
const AGENT_X = 150;
const HUB_L = 205;
const HUB_R = 355;
const OUT_X = 410;

const agents = [
  { name: "Larder bot", leash: "£60 a week", id: "grocery" },
  { name: "Office bot", leash: "£300 a month", id: "office" },
  { name: "Travel bot", leash: "£400 a trip", id: "travel" },
];

const outcomes: { key: Outcome; title: string; sub: string }[] = [
  { key: "APPROVE", title: "PayPal", sub: "Paid in the sandbox" },
  { key: "TUG", title: "You", sub: "Tug to approve" },
  { key: "BLOCK", title: "Blocked", sub: "Reason on the record" },
];

/** The walk the packet takes for each step of the loop. Mirrors the demo video's three beats. */
const walks: { agent: number; outcome: Outcome; what: string; amountMinor: number }[] = [
  { agent: 0, outcome: "APPROVE", what: "Weekly staples", amountMinor: 1840 },
  { agent: 1, outcome: "TUG", what: "Printer and toner", amountMinor: 6400 },
  { agent: 0, outcome: "BLOCK", what: "Gift cards from a poisoned page", amountMinor: 50000 },
];

const inEdge = (y: number) => `M${AGENT_X},${y} C${AGENT_X + 40},${y} ${HUB_L - 35},210 ${HUB_L},210`;
const outEdge = (y: number) => `M${HUB_R},210 C${HUB_R + 35},210 ${OUT_X - 40},${y} ${OUT_X},${y}`;
const walkPath = (a: number, o: Outcome) =>
  `M${AGENT_X},${AGENT_Y[a]} C${AGENT_X + 40},${AGENT_Y[a]} ${HUB_L - 35},210 ${HUB_L},210 L${HUB_R},210 C${HUB_R + 35},210 ${OUT_X - 40},${OUT_Y[o]} ${OUT_X},${OUT_Y[o]}`;

const pct = (v: number, of: number) => `${(v / of) * 100}%`;

function useReducedMotion() {
  const [reduced] = useState(() => window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false);
  return reduced;
}

export function LeashGraph() {
  const reduced = useReducedMotion();
  const [step, setStep] = useState(0);

  useEffect(() => {
    if (reduced) return;
    const t = window.setInterval(() => setStep((s) => (s + 1) % walks.length), 3200);
    return () => window.clearInterval(t);
  }, [reduced]);

  const walk = walks[step];

  return (
    <figure className="graph" aria-label="How a purchase request travels through Leash">
      <div className="graph-stage">
        <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" aria-hidden="true">
          {AGENT_Y.map((y, i) => (
            <path key={`in${i}`} d={inEdge(y)} className={`edge ${walk.agent === i ? "is-on" : ""}`} />
          ))}
          {outcomes.map((o) => (
            <path key={o.key} d={outEdge(OUT_Y[o.key])} className={`edge edge-${o.key} ${walk.outcome === o.key ? "is-on" : ""}`} />
          ))}
          {!reduced && (
            <circle key={step} r="6" className={`packet packet-${walk.outcome}`}>
              <animateMotion dur="1.6s" fill="freeze" path={walkPath(walk.agent, walk.outcome)} calcMode="spline" keySplines="0.45 0 0.25 1" keyTimes="0;1" />
            </circle>
          )}
        </svg>

        {agents.map((a, i) => (
          <div key={a.id} className={`node node-agent id-${a.id} ${walk.agent === i ? "is-on" : ""}`}
               style={{ right: pct(W - AGENT_X, W), top: pct(AGENT_Y[i], H) }}>
            <span className="node-name"><i className="id-mark" />{a.name}</span>
            <span className="node-sub">{a.leash}</span>
          </div>
        ))}

        <div className="node node-hub" style={{ left: pct(HUB_L, W), width: pct(HUB_R - HUB_L, W), top: pct(210, H) }}>
          <span className="hub-mark"><BrandMark /></span>
          <span className="node-name">Leash</span>
          <span className="node-sub">Rules in Java, then an AI risk check</span>
        </div>

        {outcomes.map((o) => (
          <div key={`${o.key}-${o.key === walk.outcome ? step : "idle"}`}
               className={`node node-out out-${o.key} ${walk.outcome === o.key ? "is-hit" : ""}`}
               style={{ left: pct(OUT_X, W), top: pct(OUT_Y[o.key], H) }}>
            <span className="node-name"><i className="out-dot" />{o.title}</span>
            <span className="node-sub">{o.sub}</span>
          </div>
        ))}
      </div>
      <figcaption className="graph-caption" aria-live="polite">
        <span className="dim">{agents[walk.agent].name} wants</span> <span className="num">{money(walk.amountMinor, "GBP")}</span>{" "}
        <span className="dim">for</span> {walk.what.toLowerCase()}
        <span className={`pill out-${walk.outcome}`}>{OUTCOME_LABEL[walk.outcome]}</span>
      </figcaption>
    </figure>
  );
}
