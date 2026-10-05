-- FlowOps V20 — Extreme Reliability Hardening
-- Durable execution (heartbeats, worker leases, queue wait), attempt history,
-- node-level idempotency records, dead-letter records, circuit breakers, and alert delivery.

-- 1. Extend workflow_executions for worker heartbeats, worker IDs, and queue latency tracking
ALTER TABLE workflow_executions
    ADD COLUMN last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN worker_id         VARCHAR(64),
    ADD COLUMN queue_wait_ms     BIGINT;

CREATE INDEX ix_executions_status_heartbeat
    ON workflow_executions (status, last_heartbeat_at);

-- 2. Complete per-attempt execution history (attempt 1, attempt 2, attempt 3 ...)
CREATE TABLE node_execution_attempts (
    id             UUID         PRIMARY KEY,
    execution_id   UUID         NOT NULL REFERENCES workflow_executions (id) ON DELETE CASCADE,
    node_id        VARCHAR(128) NOT NULL,
    attempt_number INTEGER      NOT NULL,
    status         VARCHAR(16)  NOT NULL, -- RUNNING | SUCCEEDED | FAILED | TIMED_OUT
    error          TEXT,
    duration_ms    BIGINT,
    started_at     TIMESTAMPTZ  NOT NULL,
    finished_at    TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_node_execution_attempt UNIQUE (execution_id, node_id, attempt_number),
    CONSTRAINT ck_node_execution_attempt_status
        CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'TIMED_OUT'))
);

CREATE INDEX ix_node_attempts_execution
    ON node_execution_attempts (execution_id, node_id, attempt_number);

-- 3. Durable external side-effect idempotency records
CREATE TABLE idempotency_records (
    id               UUID         PRIMARY KEY,
    organization_id  UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    execution_id     UUID         NOT NULL REFERENCES workflow_executions (id) ON DELETE CASCADE,
    node_id          VARCHAR(128) NOT NULL,
    idempotency_key  VARCHAR(256) NOT NULL,
    attempt          INTEGER      NOT NULL DEFAULT 1,
    effect_status    VARCHAR(32)  NOT NULL, -- STARTED | COMPLETED | FAILED
    response_payload JSONB,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_idempotency_org_key UNIQUE (organization_id, idempotency_key),
    CONSTRAINT ck_idempotency_status
        CHECK (effect_status IN ('STARTED', 'COMPLETED', 'FAILED'))
);

CREATE INDEX ix_idempotency_records_exec_node
    ON idempotency_records (execution_id, node_id);

-- 4. Durable Dead Letter / Failed Work repository
CREATE TABLE dead_letter_records (
    id                    UUID         PRIMARY KEY,
    organization_id       UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    workflow_id           UUID         NOT NULL REFERENCES workflows (id) ON DELETE CASCADE,
    workflow_version_id   UUID         NOT NULL REFERENCES workflow_versions (id) ON DELETE CASCADE,
    execution_id          UUID         NOT NULL REFERENCES workflow_executions (id) ON DELETE CASCADE,
    failed_node_id        VARCHAR(128),
    failed_node_type      VARCHAR(64),
    attempts              INTEGER      NOT NULL DEFAULT 1,
    error                 TEXT,
    original_input        JSONB        NOT NULL,
    execution_context     JSONB,
    status                VARCHAR(32)  NOT NULL DEFAULT 'NEW', -- NEW | INVESTIGATING | RESOLVED | REPLAYED
    replayed_execution_id UUID,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_dead_letter_status
        CHECK (status IN ('NEW', 'INVESTIGATING', 'RESOLVED', 'REPLAYED'))
);

CREATE INDEX ix_dead_letter_org_status
    ON dead_letter_records (organization_id, status, created_at DESC);
CREATE INDEX ix_dead_letter_workflow
    ON dead_letter_records (workflow_id, created_at DESC);

-- 5. Persistent Circuit Breakers for external dependencies
CREATE TABLE circuit_breakers (
    id                   UUID         PRIMARY KEY,
    organization_id      UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    target_dependency    VARCHAR(128) NOT NULL,
    state                VARCHAR(16)  NOT NULL DEFAULT 'CLOSED', -- CLOSED | OPEN | HALF_OPEN
    failure_count        INTEGER      NOT NULL DEFAULT 0,
    success_count        INTEGER      NOT NULL DEFAULT 0,
    failure_threshold    INTEGER      NOT NULL DEFAULT 5,
    recovery_timeout_ms  BIGINT       NOT NULL DEFAULT 30000,
    last_failure_at      TIMESTAMPTZ,
    last_state_change_at TIMESTAMPTZ  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_circuit_breaker_org_target UNIQUE (organization_id, target_dependency),
    CONSTRAINT ck_circuit_breaker_state
        CHECK (state IN ('CLOSED', 'OPEN', 'HALF_OPEN'))
);

CREATE INDEX ix_circuit_breakers_org
    ON circuit_breakers (organization_id, state);

-- 6. Alert Delivery Tracking & Deduplication
CREATE TABLE alert_deliveries (
    id              UUID         PRIMARY KEY,
    organization_id UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    incident_id     UUID,
    channel         VARCHAR(32)  NOT NULL, -- WEBHOOK | SLACK | EMAIL | PAGERDUTY
    destination     VARCHAR(256) NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING', -- PENDING | DELIVERED | FAILED
    attempts        INTEGER      NOT NULL DEFAULT 0,
    error           TEXT,
    dedup_key       VARCHAR(256),
    payload         JSONB,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_alert_delivery_status
        CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED'))
);

CREATE INDEX ix_alert_deliveries_org_status
    ON alert_deliveries (organization_id, status, created_at DESC);
CREATE INDEX ix_alert_deliveries_dedup
    ON alert_deliveries (dedup_key, created_at DESC);
