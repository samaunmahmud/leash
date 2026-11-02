// Where Leash fits. Each row: the agent, the leash it runs on, and the moment Leash earns its keep.
const CASES = [
  { id: "grocery", agent: "Weekly groceries", leash: "£60 a week · supermarkets only · auto-pay to £25", moment: "A £32 big shop comes to you as a tug; the usual £18 basket just goes through." },
  { id: "office", agent: "Office supplies for a small business", leash: "£300 a month · approved suppliers · no electronics", moment: "A £64 printer order needs your nod; a laptop never gets past the rules." },
  { id: "subs", agent: "Subscription guardian", leash: "Renew existing plans · new plans always ask", moment: "Renewals pay themselves. A surprise annual upgrade waits for you." },
  { id: "travel", agent: "Travel booking", leash: "£400 a trip · rail, airlines, hotels · quiet 00:00–06:00", moment: "A 2am fare drop still books, but only after you approve it in the morning." },
  { id: "gifts", agent: "Birthday gifts", leash: "£30 a person · gift cards blocked", moment: "No page can talk the agent into gift cards, however it's worded." },
  { id: "research", agent: "Research agent buying data", leash: "£50 a month · 3 purchases an hour", moment: "A runaway loop buying API credits is stopped at the fourth attempt." },
];

const DEFENCES = [
  ["Prompt injection", "Hidden text on a product page telling the agent to buy something"],
  ["Look-alike shops", "\"Tesc0\" is not on your allowed list, so it never gets paid"],
  ["Price spikes", "Three times the agent's usual spend in a category asks you first"],
  ["Runaway loops", "Velocity limits and duplicate checks stop repeat orders"],
  ["Snap back", "One click revokes an agent; its next request is refused"],
];

export function UseCases() {
  return (
    <section id="uses" className="container section">
      <div className="section-head">
        <h2>Where Leash fits</h2>
        <p className="muted">Any job you'd hand an agent if you didn't have to hand over your card.</p>
      </div>

      <div className="card">
        <ul className="cases">
          {CASES.map((c) => (
            <li key={c.id}>
              <h3>{c.agent}</h3>
              <p className="case-leash">{c.leash}</p>
              <p className="muted">{c.moment}</p>
            </li>
          ))}
        </ul>
      </div>

      <div className="defences">
        <h3>What it stops, whichever agent you use</h3>
        <dl>
          {DEFENCES.map(([t, d]) => (
            <div key={t}><dt>{t}</dt><dd>{d}</dd></div>
          ))}
        </dl>
      </div>
    </section>
  );
}
