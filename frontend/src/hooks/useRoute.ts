import { useEffect, useState } from "react";

export type Tab = "leashes" | "tugs" | "ledger" | "report" | "new";
export type Route = { page: "landing" } | { page: "app"; tab: Tab };

const TABS: Tab[] = ["leashes", "tugs", "ledger", "report", "new"];

/** "#/app/tugs" → app/tugs. Anything not under #/app (including landing anchors like #ledger) is the landing page. */
export function parseRoute(hash: string): Route {
  const m = hash.match(/^#\/app(?:\/([a-z]+))?/);
  if (!m) return { page: "landing" };
  const tab = (TABS as string[]).includes(m[1] ?? "") ? (m[1] as Tab) : "leashes";
  return { page: "app", tab };
}

export const tabHref = (tab: Tab) => `#/app/${tab}`;

/** Hash routing: no router dependency, works from a static file, and the backend never sees the route. */
export function useRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener("hashchange", onChange);
    return () => window.removeEventListener("hashchange", onChange);
  }, []);
  return route;
}
