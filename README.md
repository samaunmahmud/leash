<h1 align="center">Leash</h1>

<p align="center"><b>Let your agents shop. Keep them on a leash.</b></p>

<p align="center">
Spending limits for AI agents, enforced through PayPal.<br>
Give each agent a mandate in plain English. Every purchase it attempts is checked by a deterministic policy engine and an AI risk check, then paid through PayPal, sent to you for approval, or blocked, with the reason on the record.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/status-in_development-8a1538?style=flat-square" alt="Status: in development" />
  <img src="https://img.shields.io/badge/PayPal-Sandbox-0070e0?style=flat-square" alt="PayPal Sandbox" />
  <img src="https://img.shields.io/badge/Java_21-ED8B00?style=flat-square&logo=openjdk&logoColor=white" alt="Java 21" />
  <img src="https://img.shields.io/badge/Spring_Boot_4-6DB33F?style=flat-square&logo=springboot&logoColor=white" alt="Spring Boot 4" />
  <img src="https://img.shields.io/badge/React_18-20232A?style=flat-square&logo=react&logoColor=61DAFB" alt="React 18" />
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green.svg?style=flat-square" alt="License: MIT" /></a>
</p>

<p align="center">Built for the <b>PayPal AI Hackathon: Build What's Next with PayPal and AI</b></p>

> [!NOTE]
> Leash runs against the **PayPal sandbox** only. No real money moves.

---

## The problem

AI agents can now browse, compare and check out. But handing one your card is all-or-nothing: either it can spend anything, or it can't spend at all. And agents can be manipulated by what they read on a web page.

## What Leash does

| Step | What happens |
|---|---|
| **Leash an agent** | Write a mandate like *"Groceries, £60 a week, supermarkets only, ask me over £25"*. AI turns it into rules; you confirm them. |
| **The agent asks first** | Any agent calls Leash before spending, over MCP or REST, with its own agent key. |
| **Policy engine** | Deterministic Java rules: length (period limit), slack (auto-approve threshold), merchants, categories, velocity, quiet hours. |
| **AI risk check** | Reads the task, the basket and the page the agent came from. It can only make a decision *stricter*, never looser. |
| **PayPal executes** | Approved purchases become PayPal sandbox orders. Borderline ones send you a **tug** to approve. |
| **Ledger** | Every attempt, paid or blocked, is recorded. Its reason card shows each check's verdict (rules, risk checks, AI, final), leads with the worst reason, and quotes what a poisoned page tried to hide. |
| **Snap back** | One click revokes an agent instantly. |

## Use cases

- Weekly grocery agent with a £60 leash
- Small-business office-supplies agent with allowlisted suppliers
- Subscription guardian that renews existing plans but tugs on new ones
- Travel agent with a per-trip budget and quiet hours
- Gift agent that can never buy gift cards
- Research agent buying data and API credits, with a velocity limit
- Defence against prompt injection, look-alike merchants, price spikes and duplicate orders

## Run it locally

Requirements: Java 21, Maven 3.9+, Node 20+, Docker (for Postgres).

```bash
cp .env.example .env            # add your PayPal sandbox credentials
docker compose up -d            # Postgres 17 on localhost:5432, data kept in a Docker volume
cd backend && mvn spring-boot:run
# new terminal
cd frontend && npm install && npm run dev
```

Open http://localhost:5173. The API runs on http://localhost:8080 (`/api/health`).

AI is optional. Without `LEASH_LLM_*` Leash uses its rule-based mandate reader and risk checks. To turn it on with Gemini, add an [AI Studio key](https://aistudio.google.com/apikey) and keep the Gemini settings from `.env.example` (`gemini-3.5-flash-lite`, `reasoning_effort=minimal`). Any OpenAI-compatible endpoint works the same way. The free tier allows about 20 requests per model per day, and each purchase or mandate reading uses one or two.

**Webhooks (optional):** PayPal needs a public HTTPS URL, so locally put a tunnel in front of port 8080 (for example `cloudflared tunnel --url http://localhost:8080`). In your sandbox app add a webhook for `https://<tunnel host>/api/paypal/webhook` with the events `CHECKOUT.ORDER.APPROVED`, `PAYMENT.CAPTURE.COMPLETED`, `PAYMENT.CAPTURE.DENIED` and `PAYMENT.CAPTURE.REFUNDED`, and put its ID in `LEASH_PAYPAL_WEBHOOK_ID`. Then a payment is captured even if the payer closes the tab, and a refund made in the PayPal dashboard shows up in the ledger.

**Pay while you're away (optional):** on the dashboard's Leashes tab press **Save PayPal**, approve once in the sandbox with your *Personal* sandbox account, and come back. Your sandbox REST app needs **Vault** ticked under *Sandbox App Settings → Advanced options*.

No Docker? Remove the `LEASH_DB_*` lines from `.env` and the backend uses an in-memory database instead (it starts fresh on every restart).

Tests:

```bash
cd backend && mvn test
cd frontend && npm test
```

The backend tests run on H2 in PostgreSQL mode. With Docker running they also boot the app against a real Postgres (Testcontainers) and check that data survives a restart.

## Deploy (Render)

**Live demo: https://leash-6sb3.onrender.com** (PayPal sandbox, resets hourly). On Render's free plan the first
visit after a quiet spell can take a minute or two while the service starts up.

`render.yaml` is a Render Blueprint: one Docker web service (the `Dockerfile` builds the dashboard and serves it from
Spring Boot) plus a Postgres database. In Render choose **New → Blueprint**, pick this repo, and fill in the secrets it
asks for: `LEASH_OWNER_PASSPHRASE`, the PayPal sandbox client ID/secret and, optionally, an AI key and webhook ID.
For webhooks, register `https://<your service>.onrender.com/api/paypal/webhook` in the sandbox app with the four events
listed under "Run it locally", then set `LEASH_PAYPAL_WEBHOOK_ID` and redeploy.

The hosted copy runs as a **public sandbox demo** (`LEASH_DEMO_PUBLIC=true`):

- Anyone can play with the three demo leashes: run the scenario, answer tugs, snap back, press Regret, read the walk
  report. The demo leashes reset every hour, and **Reset demo** puts them back at once.
- **Owner mode** (the passphrase) is needed to issue agent keys, save or forget PayPal, and create or edit leashes.
- Writes are rate-limited per visitor, demo runs per hour, and AI calls stay under `LEASH_LLM_MAX_CALLS_PER_DAY`.
- The Larder bot gets three past-week tugs, labelled "seeded example", so mandate learning has history to work from.
  They have no PayPal IDs because no payment was made.

Locally none of this is on unless you set those variables. Set `LEASH_OWNER_PASSPHRASE` alone to guard owner actions
without the rest of the public mode.

## Connect any agent (MCP)

Leash is an MCP server (Streamable HTTP at `http://localhost:8080/mcp`), so any MCP-capable agent can be put on a leash. Each agent authenticates with its own key, and the key decides which leash it spends under: one agent can never spend under another's.

1. In the dashboard, open **Leashes** and press **Agent key** on a leash card. The key (`lk_…`) is shown once; Leash stores only its hash. Pressing it again replaces the key.
2. Point your MCP client at the URL with `Authorization: Bearer <key>`.

| Tool | What it does |
|---|---|
| `request_purchase` | Ask before buying. Answers `APPROVE` (paid, or confirmed in PayPal), `TUG` (the owner was asked; call again with the same `requestId` to see the answer) or `BLOCK` (refused; the agent is told never to retry it). `amount` is a decimal string such as `"18.40"`, never a float. |
| `check_budget` | Length, spent, waiting for approval, left, and the auto-approve amount. |
| `get_mandate` | The leash's rules in a form a model can plan with. |

**Claude Desktop.** Claude Desktop's config file launches local servers, so use the [`mcp-remote`](https://github.com/geelen/mcp-remote) bridge. Add this to `~/Library/Application Support/Claude/claude_desktop_config.json` (macOS) and restart Claude Desktop:

```json
{
  "mcpServers": {
    "leash": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote", "http://localhost:8080/mcp",
        "--header", "Authorization:${LEASH_AGENT_KEY}",
        "--allow-http", "--transport", "http-only"
      ],
      "env": { "LEASH_AGENT_KEY": "Bearer lk_your_agent_key" }
    }
  }
}
```

(`--allow-http` because the local server is plain HTTP. On a deployed HTTPS Leash, drop it and use the `https://…/mcp` URL.)

**Check it with the MCP Inspector:**

```bash
npx @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer lk_your_agent_key" --method tools/list
npx @modelcontextprotocol/inspector --cli http://localhost:8080/mcp --transport http \
  --header "Authorization: Bearer lk_your_agent_key" --method tools/call --tool-name request_purchase \
  --tool-arg merchant=Larder --tool-arg category=groceries --tool-arg "description=Milk, eggs, bread" \
  --tool-arg 'amount="18.40"' --tool-arg "task=Buy this week's groceries"
```

(Quote the amount as shown, `'amount="18.40"'`: the Inspector otherwise sends it as a number and the tool's schema refuses it.)

The REST endpoint `POST /api/purchases` takes the same `Authorization: Bearer` key.

## How it's built

- **Backend:** Java 21, Spring Boot 4. A pure policy engine (no framework code) with unit tests, and a PayPal REST client (OAuth, Orders v2). Postgres with Flyway migrations, plain JDBC.
- **AI:** any OpenAI-compatible model for the mandate parser and the risk checker. Structured JSON output, validated, with a heuristic fallback.
- **Mandate learning + walk report:** when you keep approving tugs just above an agent's slack, Leash suggests a higher one ("You approved 4 tugs for groceries between £26.50 and £29. Raise auto-approve to £30?"). The number is plain arithmetic over your answered tugs: at least 3 approvals held only for being over the slack, no denial anywhere up to the new value, rounded to a sensible step, never above the length or the per-purchase cap. One click applies it and it's logged under Leash changes in the ledger. The weekly walk report per agent shows spend against the length, approved / paid / tugged / blocked counts, the blocks worth knowing about with their evidence, and any suggestion. The AI may only word the sentences: every number in its text must be one Leash computed (otherwise a template is used), and it never sees shop pages or other untrusted text.
- **Frontend:** React 18, TypeScript, Vite. The ledger is AG Grid Community, themed with Leash's own colour tokens in both light and dark, with live rows over Server-Sent Events, quick search, outcome and agent filters, and a pinned total row.
- **PayPal:** Orders v2 (create and capture) and Vault v3. Save your PayPal once (setup token → payment token) and approved purchases are charged to the saved token with no popup, as merchant-initiated orders. Leash stores only PayPal's token ID and a masked email hint. Without a saved wallet, or if PayPal refuses the saved one, each purchase gets an approve link instead. Webhooks: each delivery is checked with PayPal's verify-signature API (the event goes back byte for byte), stored by event ID so a retry is applied once, and only moves an entry forward from the state it expects, so a webhook racing the return page or the Regret button can't pay or refund twice. Webhooks never create a payment; they confirm or reverse ones Leash made. **Regret** on a paid ledger row refunds it in full (one PayPal-Request-Id per entry, so a double click refunds once) and frees that much of the leash.

## License

[MIT](LICENSE)
