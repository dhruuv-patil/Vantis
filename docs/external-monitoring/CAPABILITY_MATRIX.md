# External Workflow Automation Capability Matrix

This document defines the formal capability matrix and degradation contracts across external automation platforms supported by Vantis: **n8n**, **Make** (formerly Integromat), and **Zapier**.

Vantis is the reliability layer for automation. A core tenet of Vantis is **honesty in observability**: unsupported telemetry dimensions must be reported as **"Unavailable"** and must never fabricate zero values, empty structures, or false success indicators.

---

## 1. Provider Comparison Matrix

| Monitoring Dimension | n8n (Self-Hosted / Cloud) | Make (REST API v2) | Zapier (Webhook Instrumented) |
| :--- | :--- | :--- | :--- |
| **Connection Authentication** | API Key (`X-N8N-API-KEY`) | API Token (`Token <token>`) | Webhook HMAC Secret (`X-Vantis-Signature`) |
| **Workflow / Scenario Discovery** | **Supported** (`GET /api/v1/workflows`) | **Supported** (`GET /api/v2/scenarios`) | **Unsupported** (Zapier API does not expose user Zaps) |
| **Execution History Polling** | **Supported** (`GET /api/v1/executions`) | **Supported** (`GET /api/v2/executions`) | **Unsupported** (No run history polling API) |
| **Incremental Sync / Cursors** | **Supported** (Execution ID & timestamp cursor) | **Unsupported** (Bounded page polling with idempotency) | **N/A** (Real-time push via inbound webhook) |
| **Step / Node-Level Execution Data** | **Supported** (`GET /api/v1/executions/{id}` includes step run data) | **Unsupported** (Public API v2 returns scenario run only, empty node list) | **Optional** (If step events are posted in payload; scenario-level by default) |
| **Output Item Counts** | **Supported** (Computed from node output arrays `json` / `binary`) | **Unsupported** (Public API does not expose item counts) | **Supported** (If provided in webhook telemetry payload) |
| **Output Payload Structure / Schema** | **Supported** (Fingerprinted JSON schema hash) | **Unsupported** (Step payloads omitted in API response) | **Supported** (If provided in webhook telemetry payload) |
| **Error Details** | **Supported** (Node error message, code, stack, HTTP status) | **Supported** (Scenario-level error message and code) | **Supported** (Error message and code passed in webhook) |
| **Schedule / Trigger Discovery** | **Supported** (Parsed from Trigger nodes & cron rules) | **Unsupported** (Scenario scheduling metadata not exposed in public API) | **Unsupported** (Schedule metadata not exposed) |
| **Inbound Webhook Instrumentation** | **Optional** (Polling preferred; webhooks optional) | **Optional** (Polling preferred) | **Primary Integration Method** (`POST /api/webhooks/zapier/{id}`) |
| **Rate Limits & Backoff** | Configurable per instance; 429 backoff supported | Enforced by Make (HTTP 429 with `Retry-After`) | Enforced by Vantis ingress (120 req/min per integration) |
| **Provider Data Retention** | Configurable by self-hoster (default unlimited / 30d) | 30 days default on Make | Depends on Zapier plan (Zap history retained 14–30 days) |

---

## 2. Platform Deep Dives & Ingestion Semantics

### 2.1 n8n
- **Primary Mechanism:** Polling sync (`IntegrationSyncService`) with bounded batch execution retrieval and capped parallelism for node details (max 4 concurrent requests).
- **Execution Details:** `GET /api/v1/executions/{id}?includeData=true` returns the full run graph including all node execution records, output items, and error objects.
- **Item Count & Output Extraction:** Extracted via `N8nPayloadExtractor` by counting array elements in node outputs and generating recursive schema fingerprints capped at depth 6.
- **Completeness Marking:** Marked as `COMPLETE` unless truncated by provider (`DATA_PRUNED`) or when payloads exceed size limits (`TRUNCATED`).

### 2.2 Make (Integromat)
- **Primary Mechanism:** Polling sync (`MakeClient.listExecutions`). Make's public REST API v2 exposes scenario-level execution logs.
- **Response Envelopes:** Make API v2 responses vary across endpoints and versions. Mappers accept envelopes:
  - `{"executions": [...]}`
  - `{"scenarioLogs": [...]}`
  - `{"logs": [...]}`
  - `{"data": [...]}`
  - Direct root array `[...]`
- **Status Codes:** Numeric status codes (`1` = Success, `2` = Warning, `3` = Failed) as well as text statuses (`success`, `warning`, `failed`, `running`, `canceled`).
- **Node-Level Execution Data:** Make's public API does not expose step-by-step node execution payloads without proprietary internal tokens. Therefore, `MakeProvider.fetchNodeExecutions()` returns an empty list.
- **Degradation Semantics:**
  - Vantis generates a synthetic scenario-level event (`workflow_run`) representing the entire scenario execution.
  - `dataCompleteness` is explicitly recorded as `NODE_DATA_UNAVAILABLE`.
  - Metadata includes `nodeExecutionData: "UNAVAILABLE"` with `nodeExecutionDataReason: "Provider does not expose step-level node execution payloads"`.
  - Item count and output signature are preserved as `null` ("Unavailable").

### 2.3 Zapier
- **Primary Mechanism:** Inbound Webhook Instrumentation (`ZapierWebhookIngressController`).
- **Rationale:** Zapier's developer platform and public APIs do not expose REST endpoints to discover arbitrary user Zaps or poll their run histories. Attempting to simulate polling results in empty syncs or fabricated data.
- **Ingress Design:**
  - Customers add a "Webhooks by Zapier" POST step or custom code step at the end/error branches of critical Zaps pointing to:
    `POST https://api.vantis.ai/api/webhooks/zapier/{integrationId}`
  - Ingress verifies HMAC-SHA256 signature using a pre-shared integration secret via `X-Vantis-Signature: sha256=<hex>` (or `X-Signature`).
  - Strict replay prevention enforces `X-Vantis-Timestamp` within $\pm 300$ seconds and dedupes execution nonces within a 10-minute cache window.
  - Ingested events create/update external workflows and executions with full anomaly detection and baseline support.

---

## 3. Honest Detector Degradation Rules

When a provider does not supply required telemetry dimensions, detectors in `ExternalAnomalyEngine` and `ReliabilityDetectionService` degrade gracefully rather than reporting false positive anomalies or fake zeros:

| Detector | Required Dimension | n8n Behavior | Make Behavior | Zapier Behavior |
| :--- | :--- | :--- | :--- | :--- |
| **Latency Spike Detector** | `durationMs` | **Active** (Calculated from start/finish timestamps) | **Active** (Calculated from scenario `duration` / timestamps) | **Active** (Reported via duration or timestamps) |
| **Zero-Output Detector** | `itemCount != null` | **Active** (Detects drops from $>0$ baseline to $0$) | **Disabled / Skipped** (`itemCount` is `null` / unavailable; no anomaly fired) | **Active if provided**, otherwise skipped |
| **Output Collapse Detector** | `itemCount != null` | **Active** (Detects median drop $>3 \times \text{MAD}$) | **Disabled / Skipped** (No item count metric) | **Active if provided**, otherwise skipped |
| **Schema Drift Detector** | `outputSignature != null` | **Active** (Detects structural changes in JSON output) | **Disabled / Skipped** (Output signature is `null` / unavailable) | **Active if provided**, otherwise skipped |
| **Silent Stall Detector** | Expected schedule / arrival interval | **Active** (Uses discovered schedule or inter-arrival baseline) | **Active** (Uses inter-arrival statistical baseline) | **Active** (Uses inter-arrival statistical baseline) |
| **Execution Failure Detector** | `status` | **Active** | **Active** (Maps `3` or `"failed"` to FAILED) | **Active** (Maps `"failed"` / `"error"` to FAILED) |

---

## 4. Honest UI Degradation Rules

1. **Item Counts & Output Sizes:**
   - When `itemCount` is `null`, the UI displays a subtle `"Unavailable"` badge rather than `0 Items`.
   - Tooltip: *"Output item counts are not provided by this automation platform."*
2. **Node-Level Execution Step Breakdown:**
   - When `dataCompleteness == "NODE_DATA_UNAVAILABLE"`, the execution view displays a banner:
     *"Scenario-Level Observability: Step-level execution data is not exposed by Make's public API. Workflow duration, status, and error logs are fully monitored."*
   - Step breakdown table is collapsed or shows the single scenario-level run event.
3. **Health Score & Success Rate:**
   - Health score calculations exclude unsupported detectors (e.g. schema drift) rather than treating them as 0% or 100% compliant.
