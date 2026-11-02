// A real sequence, so it's numbered.
const STEPS = [
  { t: "You write the mandate", d: "Plain English. AI turns it into rules and shows them to you before anything is saved." },
  { t: "The agent asks first", d: "Any agent calls Leash before it spends, over MCP or a simple REST call." },
  { t: "Fixed rules decide", d: "A Java policy engine checks length, slack, shops, categories, speed and quiet hours." },
  { t: "AI can only say no", d: "A risk check reads the task, the basket and the page. It can tighten a decision, never loosen it." },
  { t: "PayPal pays, or you're asked", d: "Approved purchases become PayPal sandbox orders. Anything borderline comes to you as a tug." },
];

export function HowItWorks() {
  return (
    <section id="how" className="container section">
      <div className="section-head">
        <h2>How a purchase gets through</h2>
        <p className="muted">The model never approves money. That's a rule in the code, with a test that checks every combination.</p>
      </div>
      <ol className="steps">
        {STEPS.map((s) => (
          <li key={s.t}>
            <h3>{s.t}</h3>
            <p className="muted">{s.d}</p>
          </li>
        ))}
      </ol>
    </section>
  );
}
