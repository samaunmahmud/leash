import { useCallback, useEffect, useRef, useState } from "react";
import { fetchLeashes, fetchLedger, fetchTugs } from "../api";
import type { LeashCard, LedgerEntry, TugView } from "../types";

export type StreamState = "connecting" | "live" | "offline";

export interface LeashData {
  leashes: LeashCard[];
  tugs: TugView[];
  ledger: LedgerEntry[];
  loaded: boolean;
  error: string | null;
  stream: StreamState;
  /** The entry that arrived most recently over the stream, to highlight it. */
  latestId: string | null;
  refresh: () => void;
}

/** Puts a changed entry in place (or on top, if new), newest first. */
export function upsertEntry(list: LedgerEntry[], e: LedgerEntry): LedgerEntry[] {
  const id = e.id ?? e.request.id;
  const i = list.findIndex((x) => (x.id ?? x.request.id) === id);
  if (i === -1) return [e, ...list];
  const next = list.slice();
  next[i] = e;
  return next;
}

/**
 * Loads leashes, tugs and the ledger, then keeps them live from the server's event stream (GET /api/stream).
 * Ledger events carry the entry, so the ledger updates in place; leash cards and tugs are re-fetched (debounced),
 * because one purchase changes their totals.
 */
export function useLeashData(): LeashData {
  const [leashes, setLeashes] = useState<LeashCard[]>([]);
  const [tugs, setTugs] = useState<TugView[]>([]);
  const [ledger, setLedger] = useState<LedgerEntry[]>([]);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stream, setStream] = useState<StreamState>("connecting");
  const [latestId, setLatestId] = useState<string | null>(null);
  const timer = useRef<number | undefined>(undefined);

  const refresh = useCallback(() => {
    Promise.all([fetchLeashes(), fetchTugs(), fetchLedger()])
      .then(([l, t, g]) => {
        setLeashes(l);
        setTugs(t);
        setLedger(g);
        setError(null);
      })
      .catch((e: Error) => setError(e.message))
      .finally(() => setLoaded(true));
  }, []);

  const refreshSoon = useCallback(() => {
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => {
      fetchLeashes().then(setLeashes).catch(() => {});
      fetchTugs().then(setTugs).catch(() => {});
    }, 120);
  }, []);

  useEffect(() => {
    refresh();
    if (typeof EventSource === "undefined") {
      setStream("offline");
      return;
    }
    const es = new EventSource("/api/stream");
    es.addEventListener("hello", () => setStream("live"));
    es.addEventListener("ledger", (ev) => {
      try {
        const { entry } = JSON.parse((ev as MessageEvent).data) as { type: string; entry: LedgerEntry };
        setLedger((list) => upsertEntry(list, entry));
        setLatestId(entry.id ?? entry.request.id);
      } catch {
        /* ignore a malformed event */
      }
      refreshSoon();
    });
    es.addEventListener("leash", refreshSoon);
    es.onerror = () => setStream(es.readyState === EventSource.CLOSED ? "offline" : "connecting");
    es.onopen = () => setStream("live");
    return () => {
      es.close();
      window.clearTimeout(timer.current);
    };
  }, [refresh, refreshSoon]);

  return { leashes, tugs, ledger, loaded, error, stream, latestId, refresh };
}
