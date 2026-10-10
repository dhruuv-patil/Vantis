-- Rollback V27__incident_fingerprint_and_recovery.sql

DROP INDEX IF EXISTS idx_incidents_org_fingerprint_status;
ALTER TABLE incidents DROP COLUMN IF EXISTS fingerprint;

DELETE FROM flyway_schema_history WHERE version = '27';
