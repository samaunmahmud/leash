import type { Health } from "../../types";

/** On the hosted public demo only: says up front that it's shared, resets, and can't move real money. */
export function DemoBanner({ health }: { health: Health | null }) {
  if (!health?.publicDemo) return null;
  const every = health.resetMinutes === 60 || !health.resetMinutes ? "hourly" : `every ${health.resetMinutes} minutes`;
  return (
    <div className="demo-banner" role="note">
      <div className="container">
        <span><b>Public sandbox demo</b>, resets {every}. No real money.</span>
      </div>
    </div>
  );
}
