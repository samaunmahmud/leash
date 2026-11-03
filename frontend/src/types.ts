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
}

/** The owner's saved PayPal (T11). Only a masked hint ever reaches the browser. */
export interface WalletView {
  status: "NONE" | "PENDING" | "ACTIVE";
  hint: string | null;
  approveUrl: string | null;
  paypalConfigured: boolean;
}
