# Vantis — Disaster Recovery & Backup Runbook

> [!IMPORTANT]
> **Production Reliability SLA**:
> - **RPO (Recovery Point Objective)**: **< 5 minutes** (via continuous PostgreSQL WAL archiving).
> - **RTO (Recovery Time Objective)**: **< 30 minutes** (end-to-end service restoration from cold backup or regional failover).

---

## 1. Architecture Overview & Storage Tier

Vantis executes automated workflows with durable state persistence. The persistence layer comprises:
1. **Primary Database (PostgreSQL 16+)**: Authoritative store for organizations, users, workflows, immutable workflow versions, executions, node states, execution attempt logs, idempotency records, dead-letter records, circuit breakers, and telemetry.
2. **Execution Worker Queue**: In-process executor with durable database-backed execution status and heartbeat tracking.
3. **Encrypted Credentials Store**: AES-256-GCM encrypted tokens for external providers (Slack, Stripe, GitHub, etc.).

---

## 2. Backup Strategy & Procedures

### 2.1 Continuous Point-in-Time Recovery (PITR)
- **WAL Archiving**: Continuous WAL archiving to secure object storage (e.g., AWS S3 or Google Cloud Storage) with versioning and immutable object lock enabled:
  ```ini
  # postgresql.conf
  wal_level = replica
  archive_mode = on
  archive_command = 'pgbackrest --stanza=vantis archive-push %p'
  archive_timeout = 300 # forces WAL segment switch every 5 minutes (enforcing RPO < 5m)
  ```
- **Retention**: 30 days of continuous WAL segments and daily full backups.

### 2.2 Scheduled Logical Backups
- Automated cron job runs daily at 02:00 UTC using `pg_dump`:
  ```bash
  pg_dump -Fc -v -h "$DB_HOST" -U "$DB_USER" -d "$DB_NAME" \
    --exclude-table-data="execution_logs" \
    -f "/backup/vantis_daily_$(date +%Y%m%d_%H%M%S).dump"
  ```
- Backups are encrypted at rest with AES-256 and replicated across multi-region object storage buckets.

---

## 3. Disaster Recovery & Restoration Procedures

### 3.1 Point-in-Time Database Restore
To restore PostgreSQL to a specific timestamp (e.g., prior to a catastrophic data corruption incident at `2026-10-05 14:32:00 UTC`):

1. **Stop Application Services**:
   ```bash
   kubectl scale deployment vantis-api --replicas=0
   kubectl scale deployment vantis-ai --replicas=0
   ```
2. **Restore Base Backup & WAL Target**:
   ```bash
   # Create restore target config in postgresql.conf / recovery.signal
   restore_command = 'pgbackrest --stanza=vantis archive-get %f "%p"'
   recovery_target_time = '2026-10-05 14:32:00 UTC'
   recovery_target_action = 'promote'
   ```
3. **Verify Database Integrity**:
   ```sql
   SELECT count(*) FROM workflow_executions WHERE status = 'RUNNING';
   SELECT count(*) FROM dead_letter_records;
   ```
4. **Restart Services & Trigger Automated Startup Sweep**:
   ```bash
   kubectl scale deployment vantis-api --replicas=4
   kubectl scale deployment vantis-ai --replicas=2
   ```

---

## 4. Execution Engine Recovery & Crash Invariants

### 4.1 Startup Recovery Sweep
- When the Vantis backend starts (`ApplicationStartedEvent`), `ExecutionRecoveryService.recoverOnStartup()` runs automatically:
  1. Identifies any executions in non-terminal states (`QUEUED` or `RUNNING`) left behind by deceased JVM instances.
  2. Calls `store.resetStuckNodesForRecovery(id)`, rolling back abandoned `RUNNING` nodes to `PENDING` while preserving all already-succeeded nodes and their outputs.
  3. Re-enqueues the execution on `QueueService`.
  4. Records an audit trail entry (`EXECUTION_RECOVERED`) scoped to the execution's organization.

### 4.2 Orphaned Execution & Heartbeat Sweeper
- Every worker records a periodic heartbeat timestamp (`last_heartbeat_at`) and assigns its worker ID to `workflow_executions`.
- The scheduled orphan sweeper (`ExecutionRecoveryService.sweepStaleExecutions()`, running every 60s) queries:
  ```sql
  SELECT id FROM workflow_executions
  WHERE status = 'RUNNING'
    AND last_heartbeat_at < (NOW() - INTERVAL '5 minutes');
  ```
- Any execution exceeding the stale threshold is automatically recovered, stuck nodes are safely reset, and the job is re-enqueued.

### 4.3 Durable Dead-Letter Queue (DLQ)
- Unrecoverable failures or executions that exhaust their retry budgets are never silently dropped.
- The failure pipeline captures the complete payload, original trigger data, failed node, attempt count, and error context in `dead_letter_records`.
- Operators can inspect and safely replay dead-letter items via:
  - `GET /api/dead-letters`
  - `POST /api/dead-letters/{id}/replay`

---

## 5. Safe Replay & External Side-Effect Guardrails

When replaying a workflow:
1. **Side-Effect Inspection**: Downstream nodes are analyzed for side-effecting operations (`http_request`, `slack`, `email`, `stripe`, `database_insert`, etc.).
2. **Safe Confirmation**: Replay returns warning diagnostics and requires explicit confirmation (`forceSideEffects: true`) before mutating external systems.
3. **Idempotency Headers**: External HTTP requests utilize deterministic idempotency keys (`idempotency_records`) to prevent duplicate transactions.

---

## 6. Circuit Breakers & Self-Reliability Probes

1. **Subsystem Circuit Breakers**:
   - Monitored dependencies (external APIs, webhooks, third-party SaaS) transition to `OPEN` when consecutive failures exceed configured thresholds.
   - Half-open probe requests automatically verify service recovery before resuming full workflow throughput.
2. **Liveness & Readiness Probes**:
   - `/health`: Liveness probe ensuring container responsiveness.
   - `/health/ready`: Deep readiness probe verifying PostgreSQL connection validity (`Connection.isValid(2)`), queue availability, and AI subsystem reachability. Returns HTTP 503 if dependencies fail.
