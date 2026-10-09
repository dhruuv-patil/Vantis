-- V27: Incident fingerprint and recovery verification support
-- Adds fingerprint column to incidents table for deduplicating recurring anomalies into a single incident card.

ALTER TABLE incidents ADD COLUMN IF NOT EXISTS fingerprint VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_incidents_org_fingerprint_status
    ON incidents (organization_id, fingerprint, status);
