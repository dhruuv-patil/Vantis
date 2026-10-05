# Vantis

> **Reliability infrastructure for automation.**

Vantis is reliability infrastructure for automation — helping teams build, monitor, detect abnormal behavior, and investigate workflow failures.

Instead of treating automation and reliability as separate systems, Vantis brings them together:

**Build → Run → Monitor → Detect → Diagnose**

---

## Overview

Modern teams rely on automation across applications, APIs, AI services, and third-party platforms. But when those workflows fail, the debugging process is often fragmented across logs, dashboards, alerts, and multiple integrations.

Vantis provides a unified workspace for:

- ⚡ Building and executing workflows
- 🤖 Running AI-powered workflow agents
- 🔌 Connecting external automation providers
- 📊 Monitoring workflow executions
- 🔎 Detecting anomalies and reliability issues
- 🚨 Managing incidents
- 🧾 Inspecting execution evidence and logs
- 👥 Managing organizations, members, and roles

The goal is simple:

> **Make automation reliable.**

---

## Core Capabilities

### Workflow Automation

Create and execute multi-step workflows using a visual workflow model.

Vantis supports workflow capabilities including:

- Triggers
- Actions
- Conditional logic
- HTTP/API operations
- Webhooks
- Notifications
- AI/LLM nodes
- External workflow providers
- Execution tracking
- Idempotency
- Recovery handling

---

### AI Workflow Agents

Vantis includes an AI agent framework that allows workflows to incorporate intelligent execution.

AI capabilities include:

- LLM-powered workflow nodes
- Agent execution
- Tool calling
- Provider-agnostic AI architecture
- AI-assisted workflow operations

The architecture is designed so AI providers can be extended without tightly coupling the workflow engine to a single model provider.

---

### Reliability & Anomaly Detection

Vantis doesn't stop when a workflow is deployed.

It continuously works with execution data to provide operational visibility.

Reliability capabilities include:

- Execution monitoring
- Baseline tracking
- Anomaly detection
- Incident management
- Reliability status
- Execution history
- Observability data
- Evidence-oriented investigation

This enables teams to move from:

> "Something failed."

to:

> "What failed, why did it fail, and what evidence supports that conclusion?"

---

### Integrations

Vantis is designed around a provider-agnostic integration architecture.

Supported integration categories include:

- n8n
- Zapier
- Make
- GitHub
- Slack
- Discord
- Microsoft Teams
- Email / SMTP
- Webhooks

The integration layer separates provider-specific implementations from the core workflow system, making additional providers easier to add.

---

## Architecture

Vantis is composed of three primary services:

```text
                         ┌─────────────────────┐
                         │      Frontend       │
                         │   Next.js / React   │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │      Backend        │
                         │   Spring Boot API   │
                         └──────┬───────┬──────┘
                                │       │
                    ┌───────────┘       └──────────────┐
                    ▼                                  ▼
          ┌─────────────────┐                ┌─────────────────┐
          │    Database     │                │  Integrations   │
          │   PostgreSQL    │                │ n8n / Make /    │
          └─────────────────┘                │ Zapier / GitHub │
                                             └─────────────────┘

                                │
                                ▼
                         ┌─────────────────────┐
                         │     AI Service      │
                         │ Provider-agnostic   │
                         │   AI / Agent Layer  │
                         └─────────────────────┘
```

---

## Billing & Pricing (Private Beta to Paid Launch)

Vantis features a localized, multi-currency billing and pricing engine with intentional regional pricing (no live FX conversions) and currency stability for international teams.

### Current Status: Private Beta
- `PRIVATE_BETA=true`: All Pro features are unlocked and complimentary for preview workspaces.
- `BILLING_ENABLED=false`: Checkout and payment operations are safely disabled.
- The UI displays planned regional prices struck through alongside localized `₹0 / $0` amounts and the banner: **"Private Beta — Pro features are currently free."**

### How to Launch Paid Billing
To activate paid billing without redesign or database migrations:
1. **Flip the two configuration environment variables**:
   ```bash
   PRIVATE_BETA=false
   BILLING_ENABLED=true
   ```
2. **Plug in your Payment Provider Adapter**:
   - The codebase defines a pluggable `BillingProvider` interface in [`frontend/lib/billing/provider.ts`](file:///Users/dhruvpatil/Projects/flowforge-ai/frontend/lib/billing/provider.ts).
   - Replace or register your production provider adapter (e.g., Stripe, Razorpay) against `defaultBillingProvider`.
   - Provide standard provider credentials (e.g., API keys, webhook signing secrets).
3. **Restart the application**:
   - Existing workspaces seamlessly transition from `PRIVATE_BETA` to paid tiers (`PRO` or `BUSINESS`) on the same workspace and subscription record with full data integrity.

---

Website: [https://vantis.run](https://vantis.run)
