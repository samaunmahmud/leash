import type {
  DemoLedger,
  Health,
  LeashCard,
  LedgerEntry,
  MandateInput,
  ParsedMandate,
  ProblemDetail,
  TugView,
  WalletView,
  AgentKey,
  LeashChange,
  WalkReport,
} from "./types";
import { SAMPLE_LEDGER } from "./lib/sample";
import { ownerPassphrase, setOwnerPassphrase } from "./lib/owner";

// Every call to the backend lives here, so components never build URLs themselves.

/** Fired on window when an owner-only call was refused for want of the passphrase. */
export const OWNER_NEEDED = "leash:owner-needed";

/** An API error carrying the server's problem-JSON sentence, so the UI can show it as-is. */
export class ApiError extends Error {
  constructor(
    message: string,
    readonly status: number,
    readonly code?: string,
  ) {
    super(message);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const owner = ownerPassphrase();
  const res = await fetch(path, {
    method,
    headers: {
      Accept: "application/json",
      ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
      ...(owner ? { "X-Leash-Owner": owner } : {}),
    },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  if (!res.ok) {
    let msg = `${path} answered ${res.status}`;
    let code: string | undefined;
    try {
      const p = (await res.json()) as ProblemDetail;
      if (p.detail) msg = p.detail;
      code = p.code;
    } catch {
      /* not JSON */
    }
    if (res.status === 429 && !code) msg = "Too many requests. Wait a moment and try again.";
    // A remembered passphrase that's now refused (changed on the server): forget it so the unlock button shows again.
    if (code === "OWNER_REQUIRED" && owner) setOwnerPassphrase(null);
    // Owner-only action while locked: the Owner mode control opens itself so the next step is obvious.
    if (code === "OWNER_REQUIRED" && typeof window !== "undefined") window.dispatchEvent(new Event(OWNER_NEEDED));
    throw new ApiError(msg, res.status, code);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

const get = <T>(path: string) => request<T>("GET", path);
const post = <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {});
const put = <T>(path: string, body: unknown) => request<T>("PUT", path, body);
const del = <T>(path: string) => request<T>("DELETE", path);

export async function fetchHealth(): Promise<Health | null> {
  try {
    return await get<Health>("/api/health");
  } catch {
    return null;
  }
}

/** Live decisions from the engine when the API is up; the same scenario from a sample when it isn't. */
export async function fetchDemoLedger(): Promise<{ data: DemoLedger; live: boolean }> {
  try {
    return { data: await get<DemoLedger>("/api/demo/ledger"), live: true };
  } catch {
    return { data: SAMPLE_LEDGER, live: false };
  }
}

/** Checks the owner passphrase and remembers it for this tab when it's right. */
export async function unlockOwner(passphrase: string): Promise<void> {
  const res = await fetch("/api/owner/check", { method: "POST", headers: { "X-Leash-Owner": passphrase } });
  if (res.status === 204) {
    setOwnerPassphrase(passphrase);
    return;
  }
  let msg = res.status === 429 ? "Too many attempts. Wait a few minutes." : "That passphrase isn't right.";
  try {
    const p = (await res.json()) as ProblemDetail;
    if (p.detail) msg = p.detail;
  } catch {
    /* not JSON */
  }
  throw new ApiError(msg, res.status);
}

// ── Leashes ──
export const fetchLeashes = () => get<LeashCard[]>("/api/leashes");
export const snapBack = (agentId: string) => post<LeashCard>(`/api/leashes/${encodeURIComponent(agentId)}/snap`);
export const releash = (agentId: string) => post<LeashCard>(`/api/leashes/${encodeURIComponent(agentId)}/releash`);
export const createLeash = (agentName: string, kind: string, mandate: MandateInput) =>
  post<LeashCard>("/api/leashes", { agentName, kind, mandate });
export const updateMandate = (agentId: string, mandate: MandateInput) =>
  put<LeashCard>(`/api/leashes/${encodeURIComponent(agentId)}/mandate`, mandate);
export const issueAgentKey = (agentId: string) => post<AgentKey>(`/api/leashes/${encodeURIComponent(agentId)}/key`);
export const parseMandate = (text: string) => post<ParsedMandate>("/api/mandates/parse", { text });

// ── Tugs ──
export const fetchTugs = () => get<TugView[]>("/api/tugs");
export const approveTug = (id: string) => post<TugView>(`/api/tugs/${encodeURIComponent(id)}/approve`);
export const denyTug = (id: string) => post<TugView>(`/api/tugs/${encodeURIComponent(id)}/deny`);

// ── Ledger ──
export const fetchLedger = (agentId?: string) =>
  get<LedgerEntry[]>(`/api/ledger${agentId ? `?agentId=${encodeURIComponent(agentId)}` : ""}`);
export const captureEntry = (id: string) => post<LedgerEntry>(`/api/ledger/${encodeURIComponent(id)}/capture`);
export const refundEntry = (id: string) => post<LedgerEntry>(`/api/ledger/${encodeURIComponent(id)}/refund`);

// ── Mandate learning + walk reports ──
export const fetchWalkReports = () => get<WalkReport[]>("/api/walk-reports");
export const fetchLeashChanges = () => get<LeashChange[]>("/api/leash-changes");
export const acceptSuggestion = (agentId: string, toMinor: number) =>
  post<LeashChange>(`/api/leashes/${encodeURIComponent(agentId)}/suggestion/accept`, { toMinor });
export const dismissSuggestion = (agentId: string, toMinor: number) =>
  post<void>(`/api/leashes/${encodeURIComponent(agentId)}/suggestion/dismiss`, { toMinor });

// ── Demo ──
export const runDemo = () => post<unknown>("/api/demo/run?paceMs=1800&reset=true");
export const resetDemo = () => post<void>("/api/demo/reset");

// ── Saved PayPal (pay while you're away) ──
export const fetchWallet = () => get<WalletView>("/api/wallet");
export const startWallet = () => post<WalletView>("/api/wallet/setup");
export const completeWallet = () => post<WalletView>("/api/wallet/complete");
export const forgetWallet = () => del<WalletView>("/api/wallet");
