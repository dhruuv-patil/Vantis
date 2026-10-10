-- ============================================================================
-- V24 — Execution History Locking for Plan Retention Compliance
-- ============================================================================

ALTER TABLE workflow_executions
    ADD COLUMN IF NOT EXISTS is_locked BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS locked_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_executions_is_locked
    ON workflow_executions (organization_id, is_locked);
