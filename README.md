# Vantis

**Reliability infrastructure for automation.**

Vantis brings workflow automation and reliability engineering into one workspace, so teams can build workflows, monitor them, detect abnormal behavior, and investigate failures with evidence.

**Build → Run → Monitor → Detect → Diagnose**

[Website](https://vantis.run) · [Documentation](#documentation) · [Contributing](#contributing) · [Security](#security)

> **Status:** Private Beta. All Pro features are free for preview workspaces. See [Billing](#billing--pricing).

---

## Table of Contents

- [Why Vantis](#why-vantis)
- [Features](#features)
- [Architecture](#architecture)
- [Tech Stack](#tech-stack)
- [Getting Started](#getting-started)
- [Configuration](#configuration)
- [Project Structure](#project-structure)
- [Development](#development)
- [Deployment](#deployment)
- [Billing & Pricing](#billing--pricing)
- [Security](#security)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [License](#license)

---

## Why Vantis

Teams automate across applications, APIs, AI services, and third-party platforms. When those workflows fail, debugging is scattered across logs, dashboards, alerts, and integrations.

Vantis moves teams from:

> "Something failed."

to:

> "What failed, why did it fail, and what evidence supports that conclusion?"

---

## Features

### Workflow Automation
- Visual multi-step workflow model
- Triggers, actions, and conditional logic
- HTTP/API operations and webhooks
- Notifications
- Execution tracking, idempotency, and recovery handling

### AI Workflow Agents
- LLM-powered workflow nodes
- Agent execution with tool calling
- Provider-agnostic AI layer, so model providers can be added without coupling the workflow engine to one vendor

### Reliability & Anomaly Detection
- Execution monitoring and history
- Baseline tracking and anomaly detection
- Incident management and reliability status
- Observability data and evidence-oriented investigation

### Integrations
| Category | Providers |
|---|---|
| Automation | n8n, Zapier, Make |
| Source control | GitHub |
| Messaging | Slack, Discord, Microsoft Teams |
| Email | SMTP |
| Generic | Webhooks |

The integration layer isolates provider-specific code from the core workflow system, so adding a provider does not require changes to the engine.

### Workspaces & Access
- Organizations, members, and roles
- Workspace-scoped data and billing

---

## Architecture

Vantis consists of three primary services plus external integrations.

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
                    └───┬─────────┬───┬───┘
                        │         │   │
              ┌─────────┘         │   └──────────┐
              ▼                   ▼              ▼
     ┌────────────────┐  ┌────────────────┐  ┌──────────────────┐
     │    Database    │  │   AI Service   │  │   Integrations   │
     │   PostgreSQL   │  │ Provider-      │  │ n8n / Make /     │
     │                │  │ agnostic agent │  │ Zapier / GitHub  │
     └────────────────┘  │ layer          │  └──────────────────┘
                         └────────────────┘
```

| Service | Responsibility |
|---|---|
| **Frontend** | Workspace UI, workflow builder, monitoring views, billing pages |
| **Backend** | REST API, workflow execution, anomaly detection, incidents, auth, integrations |
| **AI Service** | Agent execution, tool calling, model provider abstraction |
| **PostgreSQL** | Workflows, executions, baselines, incidents, organizations |

---

## Tech Stack

- **Frontend:** Next.js, React, TypeScript
- **Backend:** Java, Spring Boot
- **Database:** PostgreSQL
- **AI service:** Provider-agnostic agent layer
- **Integrations:** n8n, Zapier, Make, GitHub, Slack, Discord, Microsoft Teams, SMTP, webhooks

---

## Getting Started

### Prerequisites

| Tool | Version |
|---|---|
| Node.js | 20+ |
| Java (JDK) | 17+ |
| PostgreSQL | 14+ |
| Maven or Gradle | per `backend/` build file |

> Adjust versions to match your lockfiles and build configuration.

### 1. Clone

```bash
git clone <repository-url>
cd vantis
```

### 2. Configure environment

```bash
cp .env.example .env
cp frontend/.env.example frontend/.env.local
```

Fill in the values described in [Configuration](#configuration).

### 3. Start the database

```bash
createdb vantis
```

Schema migrations run automatically on backend startup.

### 4. Run the backend

```bash
cd backend
./mvnw spring-boot:run
```

### 5. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

The app is available at `http://localhost:3000`.

---

## Configuration

Never commit secrets. Use `.env` files locally and a secrets manager in production.

### Core

| Variable | Description | Required |
|---|---|---|
| `DATABASE_URL` | PostgreSQL connection string | Yes |
| `DATABASE_USER` / `DATABASE_PASSWORD` | Database credentials | Yes |
| `JWT_SECRET` | Signing secret for auth tokens | Yes |
| `FRONTEND_URL` | Allowed origin for CORS and redirects | Yes |
| `NEXT_PUBLIC_API_URL` | Backend base URL used by the frontend | Yes |

### AI

| Variable | Description | Required |
|---|---|---|
| `AI_PROVIDER` | Active AI provider | If using AI nodes |
| `AI_API_KEY` | Credential for the selected provider | If using AI nodes |

### Billing

| Variable | Description | Default |
|---|---|---|
| `PRIVATE_BETA` | Unlocks Pro features for free during preview | `true` |
| `BILLING_ENABLED` | Enables checkout and payment operations | `false` |
| Provider credentials | API keys and webhook signing secrets for your payment provider | , |

> Variable names above are placeholders. Align them with `.env.example` in your repository.

---

## Project Structure

```text
vantis/
├── frontend/          # Next.js / React application
│   └── lib/billing/   # Billing provider abstraction
├── backend/           # Spring Boot API
├── ai-service/        # AI / agent layer
├── docs/              # Additional documentation
└── .env.example
```

---

## Development

```bash
# Frontend
cd frontend
npm run lint
npm run test
npm run build

# Backend
cd backend
./mvnw test
./mvnw verify
```

Before opening a pull request, make sure linting, tests, and a production build pass.

---

## Deployment

1. Provision PostgreSQL and set the connection variables.
2. Build and deploy the backend and AI service.
3. Build and deploy the frontend with `NEXT_PUBLIC_API_URL` pointing at the backend.
4. Set `FRONTEND_URL` on the backend to the deployed frontend origin.
5. Verify health checks and run a smoke-test workflow.

Production checklist:

- [ ] Secrets stored in a secrets manager, not in the repository
- [ ] HTTPS enforced on all services
- [ ] Database backups and retention configured
- [ ] CORS limited to your frontend origin
- [ ] Webhook signing secrets set for every integration
- [ ] Monitoring and alerting enabled

---

## Billing & Pricing

Vantis includes a localized, multi-currency billing system. Prices are set per region on purpose, with no live FX conversion, so customers see stable prices in their currency.

### Current status: Private Beta

- `PRIVATE_BETA=true` unlocks all Pro features for preview workspaces at no cost.
- `BILLING_ENABLED=false` keeps checkout and payment operations disabled.
- The UI shows planned regional prices struck through next to `₹0 / $0`, with the banner **"Private Beta — Pro features are currently free."**

### Launching paid billing

No redesign or database migration is required.

1. **Flip the configuration flags**

   ```bash
   PRIVATE_BETA=false
   BILLING_ENABLED=true
   ```

2. **Register a payment provider adapter**

   Implement the `BillingProvider` interface in [`frontend/lib/billing/provider.ts`](frontend/lib/billing/provider.ts) for your provider (for example Stripe or Razorpay) and register it as `defaultBillingProvider`. Supply the provider's API keys and webhook signing secret.

3. **Restart the application**

   Existing workspaces move from `PRIVATE_BETA` to paid tiers (`PRO` or `BUSINESS`) on the same workspace and subscription record, with data intact.

---

## Security

- Do not report vulnerabilities in public issues. Email **security@vantis.run** with reproduction steps and impact.
- Credentials for integrations and AI providers are supplied through environment configuration and must never be committed.
- Verify webhook signatures for all inbound webhooks.

---

## Documentation

- Architecture and design notes: `docs/`
- API reference: `docs/api.md`
- Integration guides: `docs/integrations/`

---

## Contributing

1. Fork the repository and create a branch: `git checkout -b feat/your-change`
2. Make your change with tests.
3. Run lint, tests, and build.
4. Open a pull request describing what changed and why.

---

## License

Specify your license here (for example, MIT or proprietary) and add a `LICENSE` file to the repository.

---

<p align="center">Built to make automation reliable. · <a href="https://vantis.run">vantis.run</a></p>
