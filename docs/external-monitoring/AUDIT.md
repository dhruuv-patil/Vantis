# External Monitoring Architecture Audit

**Target System:** Vantis ("The Reliability Layer for Automation")  
**Scope:** External Workflow Monitoring (n8n, Make, Zapier), Security, Detection Pipeline, Incident Life-Cycle, AI Investigation  
**Audit Date:** 2026-10-09  
**Audit Baseline Status:**
- Backend Test Suite: 530 tests passed, 0 failures, 0 errors, 0 skipped (`./mvnw test` in 57.5s)
- AI Service Test Suite: 136 tests passed, 0 failures (`pytest` in 2.24s)
- Frontend Test Suite: 99 tests passed across 11 test suites (`vitest --run` in 4.36s)
- Frontend Type Check: 0 errors (`npm run typecheck`)
- Docker Services: `postgres`, `redis`, `ai-service`, `backend` successfully built and healthy

---

## 1. Audit Findings Table

| # | Hypothesis / Finding | Status | Code Location(s) & Evidence | Severity |
|---|----------------------|--------|-----------------------------|----------|
| 1 | n8n connection and workflow discovery work | **CONFIRMED** | `backend/src/main/java/com/flowops/integration/provider/n8n/N8nProvider.java:78-125`<br>`backend/src/main/java/com/flowops/integration/provider/n8n/N8nClient.java:62-79`<br>`backend/src/test/java/com/flowops/integration/provider/n8n/N8nProviderTest.java`<br>`testConnection` validates credentials against `/api/v1/workflows?limit=1`, and `discoverWorkflows` queries `/api/v1/workflows?limit=250` and maps correctly. | Info / Working |
| 2 | External n8n execution ingestion polls every ~60s and makes sequential per-execution HTTP requests | **CONFIRMED** | `backend/src/main/java/com/flowops/integration/sync/IntegrationSyncScheduler.java:51`<br>`backend/src/main/java/com/flowops/integration/sync/IntegrationSyncService.java:203-255`<br>Scheduler triggers every 1m (`@Scheduled(fixedDelayString = "${flowops.providers.sync-delay:1m}")`). In `sync()`, every execution in `page.items()` sequentially issues a separate blocking HTTP call `provider.fetchNodeExecutions(context, exec)` without batching, concurrency limits, or backoff. | **High** (Scaling & Rate Limit Risk) |
| 3 | External execution item counts and output signatures are not extracted correctly (relies on non-existent `outputSize`), rendering zero-output, volume-collapse and schema-drift detectors ineffective | **CONFIRMED** | `backend/src/main/java/com/flowops/integration/provider/n8n/N8nNodeExecutionMapper.java:29,90`<br>`backend/src/main/java/com/flowops/reliability/telemetry/TelemetryCollector.java:98,193-230`<br>`backend/src/main/java/com/flowops/integration/sync/IntegrationSyncService.java:486-488`<br>n8n node execution payloads return `data.resultData.runData.<NodeName>[].data.main` item arrays, not an `outputSize` property. Mapper attempts `run.path("outputSize").asLong()` which evaluates to 0/null. Furthermore, `TelemetryCollector.captureExternal` explicitly omits recording `outputSignature` (`// No outputSignature is recorded`), so `OutputDetector` never sees schema signatures or item counts. | **High** (Core Value Proposition Failure) |
| 4 | DB uniqueness constraint limits an organization to one integration per provider type | **CONFIRMED** | `backend/src/main/resources/db/migration/V5__integrations.sql:28`<br>`backend/src/main/java/com/flowops/integration/IntegrationService.java:101-105, 231-235`<br>`V5__integrations.sql` defines `CONSTRAINT uq_integrations_org_type UNIQUE (organization_id, type)`. Connecting a second instance of the same provider either fails or overwrites the first connection. | **High** (Multi-Instance Blocker) |
| 5 | n8n and Make clients have an SSRF vulnerability | **CONFIRMED** | `backend/src/main/java/com/flowops/integration/provider/n8n/N8nClient.java:51-55, 71-78, 170-174, 317-338`<br>`backend/src/main/java/com/flowops/integration/provider/make/MakeClient.java:54-58, 69-78, 131-137`<br>Both clients read `baseUrl` directly from user credentials and construct `java.net.http.HttpRequest` without resolving and verifying IP addresses, blocking cloud metadata (`169.254.169.254`), loopback (`127.0.0.1`), RFC 1918, IPv6 equivalents, or preventing DNS rebinding and open redirects. | **Critical** (Security Blocker) |
| 6 | Make node-execution ingestion returns an empty list | **CONFIRMED** | `backend/src/main/java/com/flowops/integration/provider/make/MakeProvider.java:115-120`<br>`backend/src/main/java/com/flowops/integration/sync/IntegrationSyncService.java:235-268, 448-451`<br>`MakeProvider.fetchNodeExecutions` returns `List.of()`. Because `buildBatchRequest` iterates `for (ExternalNodeExecution node : nodes)`, zero `ExecutionEvent` records are generated, so Make runs are never ingested into the event store or telemetry pipeline. | **High** (Data Ingestion Blocker) |
| 7 | Zapier has no functional execution-history monitoring | **CONFIRMED** | `backend/src/main/java/com/flowops/integration/provider/zapier/ZapierProvider.java:25-32, 62-70, 102-120`<br>Zapier's provider adapter explicitly declares `executionHistory=false`, `workflowDiscovery=false`, and throws `UnsupportedOperationException` on `fetchExecutions` and `fetchNodeExecutions`. It functions purely as an outbound webhook sink. | **Medium** (Capability Transparency) |
| 8 | External execution detail retrieval has a query-filtering bug (fixed-size org-wide limit) | **CONFIRMED** | `backend/src/main/java/com/flowops/execution/ExecutionEventController.java:60-77`<br>`backend/src/main/java/com/flowops/execution/ExecutionEventService.java:268-306`<br>`frontend/app/(app)/executions/external/[executionExternalId]/page.tsx:220-250`<br>`frontend/lib/api.ts:905-925`<br>Backend `ExecutionEventController.listEvents` does not accept `executionExternalId` query parameters and caps limit at 100. Frontend attempts to fetch 500 events org-wide and filter client-side. If more than 100 events exist org-wide since the target run, the execution cannot be resolved, resulting in a blank page / false 404. | **High** (UX & Observability Defect) |
| 9 | Incident management exists in the backend but is not connected to the external monitoring UI/flow | **CONFIRMED** | `backend/src/main/java/com/flowops/reliability/IncidentService.java:51-72`<br>`backend/src/main/java/com/flowops/reliability/IncidentController.java:19-59`<br>`backend/src/main/java/com/flowops/reliability/detect/AnomalyRecorder.java:75-100`<br>`IncidentService.create` is never called by `AnomalyRecorder` or any detector. Anomalies are recorded in `anomalies` table, but incidents are never created. The frontend has zero views or client calls consuming `/api/incidents`. | **High** (Workflow Disconnect) |
| 10 | Statistical baseline detection (median/MAD), anomaly persistence, AI investigation, notifications, billing and tenant isolation exist with varying maturity | **CONFIRMED** | `backend/src/main/java/com/flowops/reliability/baseline/RobustStats.java`<br>`backend/src/main/java/com/flowops/reliability/baseline/BaselineEngine.java`<br>`backend/src/main/java/com/flowops/ai/AiServiceClient.java:133`<br>`ai-service/app/routes/ai.py:491-560`<br>Median/MAD baseline engine and detectors are mathematically sound. Anomaly persistence works. However: AI investigation lacks strict fact vs. hypothesis grounding schemas and context budgets; multi-instance billing limits are missing; notification delivery tracking is unmonitored for external sync anomalies. | **Medium** (Maturity Gaps) |

---

## 2. Architectural Component Mapping

### 2.1 Integration Clients & Outbound HTTP
- Current clients: `N8nClient.java`, `MakeClient.java`, `ZapierClient.java`, `SlackProvider.java`, `WebhookDeliverer.java`.
- Risk: Decentralized `HttpClient` construction, missing SSRF pinning, DNS rebinding vulnerability, lack of per-host redirect validation.

### 2.2 Polling & Ingestion Pipeline
- Scheduling: `IntegrationSyncScheduler` (fixed 1m delay).
- Ingestion: `IntegrationSyncService` pulls execution list, iterates sequentially over executions, and feeds `ExecutionEventService.ingestBatch`.
- Ingestion Identity: Natural key `(integration_id, provider_workflow_id, execution_external_id, step_external_id)`.

### 2.3 Reliability Engine
- Telemetry: `TelemetryCollector` bridges `ExecutionEvent` to `NodeExecutionMetric`. Currently drops `outputSignature` and relies on empty `outputSize`.
- Baseline: `BaselineEngine` calculates rolling median, MAD, percentiles for latency, error rate, volume, and schema structure.
- Detectors: `OutputDetector`, `LatencyDetector`, `BehavioralDetector`, `VolumeDetector`, `BusinessOutcomeDetector`.
- Gating: `minSampleCount` warmup gating, `minConfidence` threshold, and deduplication keys via `AnomalyRecorder`.

### 2.4 Incidents, Notifications & Recovery
- Incidents: `Incident` entity, `IncidentRepository`, `IncidentService` with `OPEN`, `ACKNOWLEDGED`, `RESOLVED` states. Disconnected from external anomaly recording.
- Recovery Verification: `RecoveryVerificationService` tracks whether subsequent runs remain healthy before resolving issues.
- Notifications: `SlackAlertService`, `NotificationService`, webhook dispatches.

### 2.5 AI Service & Contracts
- Endpoint: `POST /ai/investigate-anomaly` in Python FastAPI service.
- Client: `AiServiceClient` in Spring Boot.
- Deficiencies: No schema-enforced grounding check to ensure hypotheses do not hallucinate non-existent error codes or fields.

### 2.6 Database & Migrations
- Active migrations: `V1` through `V25`.
- Target: Add forward-compatible migration for multi-instance integration constraints and indexes, preserving all existing org data.

---

## 3. Test Baseline Summary
- **Backend**: 530 tests passed in 57.53s. 0 failures.
- **AI Service**: 136 tests passed in 2.24s. 0 failures.
- **Frontend**: 99 tests passed across 11 files in 4.36s. 0 failures.
- **Docker Compose**: All images build cleanly and containers start in healthy state.
