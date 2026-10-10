-- ============================================================================
-- V23 — Billing Events Metadata & Webhook Out-of-Order Protection
-- ============================================================================

-- 1. Extend billing_events with organization tracking and provider event timestamps
ALTER TABLE billing_events
    ADD COLUMN IF NOT EXISTS organization_id UUID REFERENCES organizations (id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS event_timestamp TIMESTAMPTZ;

-- 2. Indexes for tenant event queries and chronological ordering
CREATE INDEX IF NOT EXISTS ix_billing_events_org_created
    ON billing_events (organization_id, created_at DESC);

CREATE INDEX IF NOT EXISTS ix_billing_events_provider_timestamp
    ON billing_events (provider, event_timestamp);

-- 3. Extend subscriptions with last_webhook_event_at to reject out-of-order stale events
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS last_webhook_event_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS ix_subscriptions_last_webhook
    ON subscriptions (last_webhook_event_at);
