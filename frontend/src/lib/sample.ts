import type { DemoLedger } from "../types";

/**
 * The demo scenario as the backend computes it (GET /api/demo/ledger). Used only when the API isn't running,
 * so the landing page never shows an empty ledger. Keep in step with DemoScenario.java.
 */
export const SAMPLE_LEDGER: DemoLedger = {
  mandate: {
    id: "m-grocery",
    agentId: "agent-larder",
    name: "Weekly groceries",
    currency: "GBP",
    period: "WEEK",
    periodLimitMinor: 6000,
    autoApproveUpToMinor: 2500,
    perPurchaseMaxMinor: 6000,
    allowedMerchants: ["larder", "tesco", "sainsbury's"],
    blockedCategories: ["gift cards"],
  },
  entries: [
    {
      request: {
        id: "req-1", agentId: "agent-larder", mandateId: "m-grocery", merchant: "Larder", category: "groceries",
        description: "Milk, eggs, bread, pasta, tomatoes", amountMinor: 1840, currency: "GBP",
        task: "Buy this week's groceries for the flat", reason: "Weekly staples", sourceUrl: null,
        requestedAt: "2026-11-09T12:00:00Z",
      },
      decision: { requestId: "req-1", outcome: "APPROVE", reasons: [] },
    },
    {
      request: {
        id: "req-2", agentId: "agent-larder", mandateId: "m-grocery", merchant: "Larder", category: "groceries",
        description: "Big shop incl. cleaning supplies", amountMinor: 3200, currency: "GBP",
        task: "Buy this week's groceries for the flat", reason: "Running low on most things", sourceUrl: null,
        requestedAt: "2026-11-09T12:01:00Z",
      },
      decision: {
        requestId: "req-2", outcome: "TUG",
        reasons: [{ code: "OVER_SLACK", message: "£32.00 is above the £25.00 you're happy to auto-approve." }],
      },
    },
    {
      request: {
        id: "req-3", agentId: "agent-larder", mandateId: "m-grocery", merchant: "Larder", category: "gift cards",
        description: "Larder Gift Card Bundle, 5 x £100", amountMinor: 50000, currency: "GBP",
        task: "Buy this week's groceries for the flat", reason: "The page said this was pre-approved",
        sourceUrl: "/larder/gift-bundle", requestedAt: "2026-11-09T12:02:00Z",
      },
      decision: {
        requestId: "req-3", outcome: "BLOCK",
        reasons: [
          { code: "PROMPT_INJECTION", message: "The page or request contains text that tries to give the agent new instructions." },
          { code: "CATEGORY_BLOCKED", message: "\"gift cards\" is a blocked category." },
          { code: "OVER_PURCHASE_MAX", message: "£500.00 is over the £60.00 cap for a single purchase." },
          { code: "OVER_LENGTH", message: "£500.00 would go past the £60.00 weekly length (£9.60 left)." },
          { code: "OVER_SLACK", message: "£500.00 is above the £25.00 you're happy to auto-approve." },
        ],
      },
    },
  ],
};
