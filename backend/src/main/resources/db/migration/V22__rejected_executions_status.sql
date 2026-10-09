-- V22: Add rejection_reason column with partial index, and billing_notices deduplication table

ALTER TABLE workflow_executions
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(64),
    ADD COLUMN IF NOT EXISTS rejection_count INT NOT NULL DEFAULT 1;

-- Partial index excluding normal runs where rejection_reason IS NULL
CREATE INDEX IF NOT EXISTS ix_executions_rejection_reason
    ON workflow_executions (rejection_reason)
    WHERE rejection_reason IS NOT NULL;

-- Authoritative deduplication of billing quota notices (80%, 100%)
CREATE TABLE IF NOT EXISTS billing_notices (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    period_start TIMESTAMPTZ NOT NULL,
    threshold INTEGER NOT NULL,
    kind VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_billing_notices UNIQUE (organization_id, period_start, threshold, kind)
);

CREATE INDEX IF NOT EXISTS ix_billing_notices_org
    ON billing_notices (organization_id, period_start);
