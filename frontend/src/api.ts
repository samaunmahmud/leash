import type { DemoLedger, Health } from "./types";
import { SAMPLE_LEDGER } from "./lib/sample";

// Every call to the backend lives here, so components never build URLs themselves.

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(path, { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`${path} answered ${res.status}`);
  return (await res.json()) as T;
}

export async function fetchHealth(): Promise<Health | null> {
  try {
    return await getJson<Health>("/api/health");
  } catch {
    return null;
  }
}

/** Live decisions from the engine when the API is up; the same scenario from a sample when it isn't. */
export async function fetchDemoLedger(): Promise<{ data: DemoLedger; live: boolean }> {
  try {
    return { data: await getJson<DemoLedger>("/api/demo/ledger"), live: true };
  } catch {
    return { data: SAMPLE_LEDGER, live: false };
  }
}
