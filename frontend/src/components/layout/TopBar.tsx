import type { Health } from "../../types";
import { useTheme } from "../../hooks/useTheme";
import { GitHubIcon, MoonIcon, SunIcon } from "../ui/Icons";
import { BrandMark } from "./Brand";

/** Tells visitors, before they click anything, whether payments will really reach the PayPal sandbox. */
function StatusPill({ health }: { health: Health | null }) {
  const live = !!health?.paypalConfigured && !!health?.sandbox;
  const label = health === null ? "Offline preview" : live ? "PayPal sandbox live" : "PayPal not connected";
  const title = live
    ? "Approved purchases create real orders in the PayPal sandbox. No real money moves."
    : "Decisions still run; payments are skipped until sandbox credentials are set.";
  return (
    <span className={`status-pill ${live ? "is-live" : "is-off"}`} title={title}>
      <i aria-hidden="true" />
      <span>{label}</span>
    </span>
  );
}

function ThemeToggle() {
  const [theme, toggle] = useTheme();
  const next = theme === "dark" ? "light" : "dark";
  return (
    <button type="button" className="icon-btn" onClick={toggle} aria-label={`Switch to ${next} theme`} title={`Switch to ${next} theme`}>
      {theme === "dark" ? <SunIcon /> : <MoonIcon />}
    </button>
  );
}

export function TopBar({ health }: { health: Health | null }) {
  return (
    <header className="topbar">
      <div className="container">
        <a className="brand" href="/" aria-label="Leash home">
          <BrandMark />
          <span className="brand-name">Leash</span>
          <span className="brand-sub">Agent spending</span>
        </a>
        <nav className="topnav" aria-label="Main">
          <a href="#ledger" className="hide-sm">Ledger</a>
          <a href="#uses" className="hide-sm">Use cases</a>
          <a href="#how">How it works</a>
          <StatusPill health={health} />
          <ThemeToggle />
          <a className="icon-link" href="https://github.com/samaunmahmud/leash" aria-label="Source on GitHub">
            <GitHubIcon />
          </a>
        </nav>
      </div>
    </header>
  );
}
