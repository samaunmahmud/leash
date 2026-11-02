// Mirrors the backend records (com.leash.policy.*). Keep in sync when the API changes.

export type Outcome = "APPROVE" | "TUG" | "BLOCK";

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
  task: string;
  reason: string;
  sourceUrl: string | null;
  requestedAt: string;
}

export interface Mandate {
  id: string;
  agentId: string;
  name: string;
  currency: string;
  period: "DAY" | "WEEK" | "MONTH" | "TOTAL";
  periodLimitMinor: number;
  autoApproveUpToMinor: number;
  perPurchaseMaxMinor: number;
  allowedMerchants: string[];
  blockedCategories: string[];
}

export interface LedgerEntry {
  request: PurchaseRequest;
  decision: Decision;
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
}
