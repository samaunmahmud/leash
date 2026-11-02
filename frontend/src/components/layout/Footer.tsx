export function Footer() {
  return (
    <footer className="footer">
      <div className="container">
        <p>
          <b>Leash</b> runs against the PayPal sandbox. No real money moves. The policy engine decides every payment;
          the AI risk check can only make a decision stricter.
        </p>
        <p>
          Built for the PayPal AI Hackathon by Samaun Mahmud ·{" "}
          <a href="https://github.com/samaunmahmud/leash">Source</a> · MIT licence
        </p>
      </div>
    </footer>
  );
}
