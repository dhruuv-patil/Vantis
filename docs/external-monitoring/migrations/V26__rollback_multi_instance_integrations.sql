-- Rollback script for V26__multi_instance_integrations.sql
--
-- CAUTION: Rolling back this migration restores the legacy 1-integration-per-provider constraint.
-- Before applying this rollback in an environment with multiple instances of a provider,
-- secondary instances must be removed or consolidated; otherwise the unique constraint
-- creation will fail on duplicate (organization_id, type) tuples.

-- 1) Drop added indexes and columns on execution_events
DROP INDEX IF EXISTS ix_execution_events_integration_created;

ALTER TABLE execution_events
    DROP COLUMN IF EXISTS run_index,
    DROP COLUMN IF EXISTS data_completeness,
    DROP COLUMN IF EXISTS http_status,
    DROP COLUMN IF EXISTS output_signature,
    DROP COLUMN IF EXISTS item_count;

-- 2) Drop added columns on integration_sync_state
ALTER TABLE integration_sync_state
    DROP COLUMN IF EXISTS backfill_cursor,
    DROP COLUMN IF EXISTS backfill_progress,
    DROP COLUMN IF EXISTS is_backfilling,
    DROP COLUMN IF EXISTS health_status,
    DROP COLUMN IF EXISTS last_error_category,
    DROP COLUMN IF EXISTS last_successful_sync_at;

-- 3) Revert multi-instance integrations schema
DROP INDEX IF EXISTS ix_integrations_org_type;
DROP INDEX IF EXISTS uq_integrations_org_type_name;

ALTER TABLE integrations
    DROP COLUMN IF EXISTS disabled_at;

-- Re-establish the legacy single-instance unique constraint
-- Note: duplicate instances per type must be deleted or deduplicated first
ALTER TABLE integrations
    ADD CONSTRAINT uq_integrations_org_type UNIQUE (organization_id, type);
