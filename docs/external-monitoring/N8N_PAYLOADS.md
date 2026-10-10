# n8n Execution Payload Specifications and Observability Guide

## 1. Overview
This document specifies the exact structure of n8n execution payloads retrieved via `GET /api/v1/executions/{id}?includeData=true`, detailing how Vantis extracts normalized node executions, item counts, structural signatures, subworkflow relationships, and error details across n8n versions (v0.x through v1.x+).

---

## 2. n8n Execution Payload Anatomy

```
Execution Response (JSON)
└── data / root
    ├── id: string (execution ID)
    ├── status: "success" | "error" | "waiting" | "running" | "crashed"
    ├── startedAt / stoppedAt: ISO-8601 timestamps
    ├── workflowId: string
    └── resultData (inside data.resultData or resultData)
        ├── lastNodeExecuted: string
        ├── error: object (execution-level error)
        └── runData: Map<NodeName, Array<NodeRun>>
            └── [NodeName] -> [ Run_0, Run_1, ... ] (for loops / retries)
                ├── startTime: epoch milliseconds
                ├── executionTime: milliseconds
                ├── executionStatus: "success" | "error" | "waiting"
                ├── error: { name, message, stack, description, httpCode }
                ├── metadata: { subExecutionId, subWorkflowId, ... }
                └── data:
                    └── main: Array<Array<Item>> (Output Branches)
                        ├── Branch 0: [ Item_0, Item_1, ... ]
                        └── Branch 1: [ Item_k, ... ] (e.g. IF / Switch false branch)
                            └── Item: { json: { ... }, binary: { ... } }
```

---

## 3. Key Telemetry Extraction Rules

### 3.1 Item Counts vs. `outputSize`
* **Defect in Legacy Code**: Legacy code read non-existent `run.outputSize`. n8n does **not** expose an `outputSize` integer property.
* **True Item Extraction**:
  1. Inspect `run.path("data")`.
  2. If `data` is missing or null (pruned execution):
     - `itemCount = null` (semantic: *telemetry unavailable*)
     - `dataCompleteness = "DATA_PRUNED"`
  3. If `data.path("main")` is an array:
     - Sum the sizes of all branch arrays within `main`.
     - An empty branch array (`[ [] ]`) yields **`itemCount = 0`**.
     - **0 items is a valid output** (e.g., filter nodes, search queries returning no results). Distinguishing `0` from `null` is vital for zero-output anomaly detection.
  4. If `data` exists but `main` is missing or not an array:
     - `itemCount = 0` (or `null` if uninstrumented).

### 3.2 Structural Signatures (Zero-PII Schema Hashes)
To enable schema-drift detection without storing sensitive customer payload data:
1. Extract property names and value data types (`string`, `number`, `boolean`, `object`, `array`, `null`) from `item.json`.
2. Sort keys lexicographically.
3. Bound depth to a maximum of 3 levels and limit to at most 50 distinct keys to prevent memory explosion.
4. Compute deterministic SHA-256 fingerprint hash of the canonical schema representation.
5. Store:
   ```json
   {
     "hash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
     "shape": { "orderId": "string", "amount": "number", "verified": "boolean" },
     "keys": ["amount", "orderId", "verified"],
     "itemCount": 1,
     "pruned": false
   }
   ```

### 3.3 HTTP Status and Error Classification
* When a node fails (e.g. HTTP Request node), n8n populates `run.error`:
  - `httpCode`: parsed into `httpStatus` (integer, e.g., 404, 500).
  - `name`: error code / class (e.g., `NodeApiError`).
  - `message`: sanitized error message.
  - `stack`: redacted / captured for incident analysis.

### 3.4 Multi-Run Nodes (Loops and Retries)
* A node run in `runData[nodeName]` is an array of iterations.
* Each run iteration is assigned its sequential `runIndex` (0, 1, 2...).
* Natural deduplication key: `(integrationId, workflowExternalId, executionExternalId, nodeName, runIndex)`.

### 3.5 Subworkflow Execution Linking
* When an Execute Workflow node runs, it stores references to the child workflow in:
  - `run.metadata.subExecutionId`
  - `run.metadata.subWorkflowId`
  - or output payload `json.childExecutionId` / `json.subExecutionId`.
* Vantis links the child execution reference into the node metadata and execution event metadata for seamless incident hierarchy traversal.

### 3.6 Payload Size & Memory Guardrails
* To prevent `OutOfMemoryError` on 100k-row execution payloads:
  - Bounded input stream cap at 10MB during download.
  - If payload exceeds 10MB or parsing detects truncation:
    - Mark `dataCompleteness = "PAYLOAD_TRUNCATED"`.
    - Retain item count if headers or partial parsing permit, otherwise mark `itemCount = null`.

---

## 4. Test Fixtures Directory Index

The fixtures in `docs/external-monitoring/n8n-payloads/` and `backend/src/test/resources/fixtures/n8n/` cover all target execution patterns:

| Fixture File | Scenario | Expected `itemCount` | Expected `status` | Expected `completeness` |
|---|---|---|---|---|
| `single_item_success.json` | Webhook + HTTP Request single item | 1 per node | `SUCCEEDED` | `COMPLETE` |
| `multi_item_success.json` | 5 inventory rows | 5 | `SUCCEEDED` | `COMPLETE` |
| `zero_items_success.json` | Filter node dropping all items | 0 | `SUCCEEDED` | `COMPLETE` |
| `error_with_details.json` | NodeApiError 404 with stack | null | `FAILED` | `COMPLETE` |
| `error_without_details.json` | Execution crashed before runData | null | `FAILED` | `COMPLETE` |
| `loops_multi_run.json` | Loop with 3 iterations (runs 0, 1, 2) | 1 per run | `SUCCEEDED` | `COMPLETE` |
| `multi_branch_if.json` | IF node with 2 output branches | 1 (branch 0: 1, branch 1: 0) | `SUCCEEDED` | `COMPLETE` |
| `subworkflow_execution.json` | Subworkflow invocation with child ID | 1 | `SUCCEEDED` | `COMPLETE` |
| `pruned_data.json` | Execution data pruned by n8n retention | null | `SUCCEEDED` | `DATA_PRUNED` |
| `wait_webhook_execution.json` | Execution paused waiting for callback | null | `WAITING` | `COMPLETE` |
| `version_0_x_legacy.json` | Legacy v0.x execution structure | 1 | `SUCCEEDED` | `COMPLETE` |
