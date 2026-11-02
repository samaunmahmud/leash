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
| **The agent asks first** | Any agent calls Leash (MCP or REST) before spending. |
| **Policy engine** | Deterministic Java rules: length (period limit), slack (auto-approve threshold), merchants, categories, velocity, quiet hours. |
| **AI risk check** | Reads the task, the basket and the page the agent came from. It can only make a decision *stricter*, never looser. |
| **PayPal executes** | Approved purchases become PayPal sandbox orders. Borderline ones send you a **tug** to approve. |
| **Ledger** | Every attempt, paid or blocked, is recorded with a plain-English reason. |
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

Requirements: Java 21, Maven 3.9+, Node 20+.

```bash
cp .env.example .env            # add your PayPal sandbox credentials
cd backend && mvn spring-boot:run
# new terminal
cd frontend && npm install && npm run dev
```

Open http://localhost:5173. The API runs on http://localhost:8080 (`/api/health`).

Tests:

```bash
cd backend && mvn test
cd frontend && npm test
```

## How it's built

- **Backend:** Java 21, Spring Boot 4. A pure policy engine (no framework code) with unit tests, and a PayPal REST client (OAuth, Orders v2).
- **AI:** any OpenAI-compatible model for the mandate parser and the risk checker. Structured JSON output, validated, with a heuristic fallback.
- **Frontend:** React 18, TypeScript, Vite.
- **PayPal:** Orders v2 (create and capture), sandbox accounts, with Vault and webhooks planned.

## License

[MIT](LICENSE)
