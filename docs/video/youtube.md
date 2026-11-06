# YouTube upload

**Title**

Leash: spending limits for AI agents, enforced through PayPal

**Visibility:** Public · **Category:** Science & Technology · **Made for kids:** No

**Description**

AI agents can shop for us now, but handing one your card is all or nothing, and an agent believes whatever a web page
tells it. Leash sits between any agent and the money.

You write a mandate in plain English ("Groceries, £60 a week, supermarkets only, ask me over £25, never gift cards").
The agent asks Leash before every purchase, over MCP or REST. A Java policy engine decides; rule-based and AI risk
checks can only make that decision stricter. Approved purchases become PayPal sandbox orders, borderline ones come to
you as a tug, and blocked ones never reach PayPal.

In this demo:
0:00 The problem
0:09 A leash in plain English
0:25 The agent shops: £18.40 approved, PayPal sandbox order
0:39 A real sandbox round trip: capture, refund, verified webhooks
0:49 A £32 tug, approved from a phone
0:59 A poisoned product page tries to buy £500 of gift cards, and Leash blocks it
1:20 Snap back: the agent's next request is refused
1:27 The ledger and the weekly walk report's slack suggestion
1:44 Owner mode and connecting agents over MCP
1:58 How it's built

Live demo (PayPal sandbox, resets hourly; the first load can take up to a minute): https://leash-6sb3.onrender.com
Code (MIT): https://github.com/samaunmahmud/leash

Built with Java 21, Spring Boot 4, PayPal Orders v2, Vault v3, webhooks and refunds (sandbox), Google Gemini
(gemini-3.5-flash-lite), MCP, React, AG Grid and Render. Built for the PayPal AI Hackathon: Build What's Next with
PayPal and AI. No real money moves anywhere in this video.

**Tags**

Leash, AI agents, agentic commerce, PayPal, PayPal sandbox, PayPal API, spending limits, prompt injection, MCP,
Model Context Protocol, Gemini, Spring Boot, Java, React, AG Grid, hackathon
