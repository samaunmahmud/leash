// Mirrors the backend records (com.leash.*). Keep in sync when the API changes.

export type Outcome = "APPROVE" | "TUG" | "BLOCK";
export type Period = "DAY" | "WEEK" | "MONTH" | "TOTAL";

export type EntryStatus =
  | "BLOCKED"
  | "TUG_PENDING"
  | "DENIED"
  | "PROCESSING"
  | "AWAITING_PAYER"
  | "PAID"
  | "PAYPAL_OFF"
  | "PAYMENT_FAILED"
  | "CANCELLED"
  | "REFUNDED";

export interface Reason {
  code: string;
  message: string;
  /** Short quote of what triggered it (e.g. the hidden text on a poisoned page). Untrusted: render as text only. */
  evidence?: string | null;
}

export interface Decision {
  requestId: string;
  outcome: Outcome;
  reasons: Reason[];
}

export interface PurchaseRequest {
  id: string;
  agentId: string;
  mandateId: string;
  merchant: string;
  category: string;
  description: string;
  amountMinor: number;
  currency: string;
  task: string | null;
  reason: string | null;
  sourceUrl: string | null;
  requestedAt: string;
}

export interface Mandate {
  id: string;
  agentId: string;
  name: string;
  currency: string;
  period: Period;
  periodLimitMinor: number;
  autoApproveUpToMinor: number;
  perPurchaseMaxMinor: number;
  allowedMerchants: string[];
  blockedMerchants?: string[];
  allowedCategories?: string[];
  blockedCategories: string[];
  maxPurchasesPerHour?: number;
  quietStart?: string | null;
  quietEnd?: string | null;
  active?: boolean;
}

/** A ledger row. The landing page's sample only has request + decision; live rows carry the rest. */
export interface LedgerEntry {
  id?: string;
  request: PurchaseRequest;
  decision: Decision;
  riskExplanation?: string | null;
  status?: EntryStatus;
  statusDetail?: string | null;
  paypalOrderId?: string | null;
  paypalCaptureId?: string | null;
  paypalRefundId?: string | null;
  approveUrl?: string | null;
  tugId?: string | null;
  createdAt?: string;
  updatedAt?: string;
}

export interface DemoLedger {
  mandate: Mandate;
  entries: LedgerEntry[];
}

export interface Health {
  status: string;
  paypalConfigured: boolean;
  sandbox: boolean;
  aiConfigured: boolean;
  vaultConnected?: boolean;
  /** The hosted public demo: show the banner. */
  publicDemo?: boolean;
  /** Owner actions need the passphrase. */
  ownerGated?: boolean;
  /** A passphrase is set; false in the public demo means owner actions are off. */
  ownerEnabled?: boolean;
  resetMinutes?: number;
}

export interface Agent {
  id: string;
  name: string;
  kind: "grocery" | "office" | "travel" | "other";
  revoked: boolean;
  revokedAt: string | null;
  createdAt: string;
}

export interface Usage {
  spentMinor: number;
  pendingMinor: number;
  periodStart: string;
}

export interface LeashCard {
  agent: Agent;
  mandate: Mandate;
  sourceText: string | null;
  usage?: Usage;
  /** Start of the agent's live key ("lk_Ab3dE9f"), null if none. The key itself is only ever shown once. */
  keyPrefix?: string | null;
}

/** A freshly issued agent key: shown once, never fetched again. */
export interface AgentKey {
  agentId: string;
  key: string;
  prefix: string;
  mcpUrl: string;
}

export interface Tug {
  id: string;
  entryId: string;
  status: "PENDING" | "APPROVED" | "DENIED";
  createdAt: string;
  resolvedAt: string | null;
}

export interface TugView {
  tug: Tug;
  entry: LedgerEntry;
}

/** What the composer sends (and what the mandate parser returns). Money in minor units. */
export interface MandateInput {
  name: string;
  currency: string;
  period: Period;
  periodLimitMinor: number;
  autoApproveUpToMinor: number;
  perPurchaseMaxMinor: number;
  allowedMerchants: string[];
  blockedMerchants: string[];
  allowedCategories: string[];
  blockedCategories: string[];
  maxPurchasesPerHour: number;
  quietStart: string | null;
  quietEnd: string | null;
  active: boolean;
  sourceText: string | null;
}

export interface ParsedMandate {
  mandate: MandateInput;
  agentName: string | null;
  kind: string | null;
  source: "ai" | "heuristic";
  notes: string[];
}

export interface ProblemDetail {
  title?: string;
  detail?: string;
  status?: number;
  /** OWNER_REQUIRED, OWNER_DISABLED or RATE_LIMITED from the owner gate. */
  code?: string;
}

/** The owner's saved PayPal (T11). Only a masked hint ever reaches the browser. */
export interface WalletView {
  status: "NONE" | "PENDING" | "ACTIVE";
  hint: string | null;
  approveUrl: string | null;
  paypalConfigured: boolean;
}

// ── Mandate learning + walk report (T16) ──

/** A suggested slack raise. Every number is Leash's own arithmetic; only `sentence` may be AI-worded (`byAi`). */
export interface SlackSuggestion {
  agentId: string;
  fromMinor: number;
  toMinor: number;
  currency: string;
  approvals: number;
  lowestApprovedMinor: number;
  highestApprovedMinor: number;
  category: string | null;
  sentence: string;
  byAi: boolean;
}

/** An accepted change to a leash, kept as an audit trail next to the ledger. */
export interface LeashChange {
  id: string;
  agentId: string;
  kind: "SLACK_RAISED";
  fromMinor: number;
  toMinor: number;
  currency: string;
  summary: string;
  evidence: string;
  createdAt: string;
}

export interface NotableBlock {
  entryId: string;
  at: string;
  description: string;
  merchant: string;
  amountMinor: number;
  currency: string;
  code: string;
  reason: string;
  evidence: string | null;
}

export interface WalkReport {
  agentId: string;
  agentName: string;
  agentKind: string;
  revoked: boolean;
  from: string;
  to: string;
  period: Mandate["period"];
  currency: string;
  lengthMinor: number;
  usedMinor: number;
  pendingMinor: number;
  requests: number;
  /** Approved by the rules without asking (paid or still waiting for PayPal). */
  approved: number;
  paid: number;
  tugged: number;
  blocked: number;
  refunded: number;
  paidMinor: number;
  notableBlocks: NotableBlock[];
  suggestion: SlackSuggestion | null;
  changes: LeashChange[];
  summary: string;
  summaryByAi: boolean;
}
