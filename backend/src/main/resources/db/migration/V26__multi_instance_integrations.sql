-- FlowOps M6 — Multi-instance integrations, health states, and normalized external telemetry.
--
-- Flyway owns the schema; spring.jpa.hibernate.ddl-auto is `validate`, so entity
-- mappings must match these columns exactly. UUIDs are generated in Java.
-- TIMESTAMPTZ always. Every table carries organization_id for tenant isolation.
--
-- Backward compatibility:
-- 1. Drops the legacy 1-per-provider constraint (uq_integrations_org_type).
-- 2. Preserves all existing integration, sync state, and execution event rows intact.
-- 3. Adds unique constraint on (organization_id, type, name) to permit multiple instances
--    of the same provider (e.g. "Prod n8n", "Staging n8n") while enforcing distinct names.

-- =========================================================================
-- 1) Drop legacy single-instance constraint & add multi-instance uniqueness
-- =========================================================================
ALTER TABLE integrations
    DROP CONSTRAINT IF EXISTS uq_integrations_org_type;

-- Soft-disable column: when set, monitoring is paused without destructive credential removal
ALTER TABLE integrations
    ADD COLUMN IF NOT EXISTS disabled_at timestamptz;

-- Unique connection name per provider type per organization
CREATE UNIQUE INDEX IF NOT EXISTS uq_integrations_org_type_name
    ON integrations (organization_id, type, name);

-- Index for org-scoped provider lookups
CREATE INDEX IF NOT EXISTS ix_integrations_org_type
    ON integrations (organization_id, type);

-- =========================================================================
-- 2) Enhance integration_sync_state for health monitoring and backfilling
-- =========================================================================
ALTER TABLE integration_sync_state
    ADD COLUMN IF NOT EXISTS last_successful_sync_at timestamptz,
    ADD COLUMN IF NOT EXISTS last_error_category varchar(50),
    ADD COLUMN IF NOT EXISTS health_status varchar(50) NOT NULL DEFAULT 'NEW',
    ADD COLUMN IF NOT EXISTS is_backfilling boolean NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS backfill_progress integer NOT NULL DEFAULT 100,
    ADD COLUMN IF NOT EXISTS backfill_cursor varchar(1000);

-- Populate last_successful_sync_at from existing last_success_at
UPDATE integration_sync_state
    SET last_successful_sync_at = last_success_at
    WHERE last_successful_sync_at IS NULL AND last_success_at IS NOT NULL;

-- Populate health_status from existing status
UPDATE integration_sync_state
    SET health_status = CASE
        WHEN status = 'HEALTHY' THEN 'HEALTHY'
        WHEN status = 'FAILED' THEN 'DEGRADED'
        ELSE 'NEW'
    END
    WHERE health_status = 'NEW' AND status IS NOT NULL;

-- =========================================================================
-- 3) Enhance execution_events for item counts, signatures, and completeness
-- =========================================================================
ALTER TABLE execution_events
    ADD COLUMN IF NOT EXISTS item_count integer,
    ADD COLUMN IF NOT EXISTS output_signature jsonb,
    ADD COLUMN IF NOT EXISTS http_status integer,
    ADD COLUMN IF NOT EXISTS data_completeness varchar(64),
    ADD COLUMN IF NOT EXISTS run_index integer DEFAULT 0;

CREATE INDEX IF NOT EXISTS ix_execution_events_integration_created
    ON execution_events (integration_id, created_at DESC);
