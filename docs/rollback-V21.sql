-- ============================================================================
-- Rollback V21 — Revert to V18 schema state
-- WARNING: Safe ONLY immediately after V21, before any new subscriptions
-- or billing data exist.
-- Preserves subscriptions_backup_v21 for verification (to be dropped in a later migration).
-- ============================================================================

BEGIN;

-- 1. Drop tables introduced in V21
DROP TABLE IF EXISTS billing_audit_logs;
DROP TABLE IF EXISTS billing_discounts;
DROP TABLE IF EXISTS invoices;
DROP TABLE IF EXISTS billing_events;
DROP TABLE IF EXISTS lifetime_usage_counters;
DROP TABLE IF EXISTS usage_counters;

-- 2. Drop columns added to organizations
ALTER TABLE organizations
    DROP COLUMN IF EXISTS tax_id,
    DROP COLUMN IF EXISTS billing_address,
    DROP COLUMN IF EXISTS billing_name;

-- 3. Drop V21 constraints from subscriptions first (prior to data restoration)
ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS ck_subscriptions_beta_period,
    DROP CONSTRAINT IF EXISTS ck_subscriptions_currency,
    DROP CONSTRAINT IF EXISTS ck_subscriptions_provider,
    DROP CONSTRAINT IF EXISTS ck_subscriptions_status,
    DROP CONSTRAINT IF EXISTS ck_subscriptions_plan;

-- 4. TRUNCATE subscriptions and restore data from subscriptions_backup_v21
TRUNCATE subscriptions;

INSERT INTO subscriptions (
    id, workspace_id, plan, status, currency, billing_country,
    billing_interval, current_period_start, current_period_end,
    provider_customer_id, provider_subscription_id, created_at, updated_at
)
SELECT
    id, workspace_id, plan, status, currency, billing_country,
    billing_interval, current_period_start, current_period_end,
    provider_customer_id, provider_subscription_id, created_at, updated_at
FROM subscriptions_backup_v21;

-- 5. Drop added columns from subscriptions
ALTER TABLE subscriptions
    DROP COLUMN IF EXISTS founding_offer_eligible_until,
    DROP COLUMN IF EXISTS beta_ends_at,
    DROP COLUMN IF EXISTS beta_started_at,
    DROP COLUMN IF EXISTS past_due_since,
    DROP COLUMN IF EXISTS cancel_at_period_end,
    DROP COLUMN IF EXISTS provider;

-- 6. Restore original V18 NOT NULL on billing_country
ALTER TABLE subscriptions
    ALTER COLUMN billing_country SET NOT NULL;

-- 7. Re-add original V18 CHECK constraints (including TRIALING)
ALTER TABLE subscriptions
    ADD CONSTRAINT ck_subscriptions_plan
        CHECK (plan IN ('FREE', 'PRIVATE_BETA', 'PRO', 'BUSINESS')),
    ADD CONSTRAINT ck_subscriptions_status
        CHECK (status IN ('ACTIVE', 'TRIALING', 'PAST_DUE', 'CANCELED', 'INCOMPLETE')),
    ADD CONSTRAINT ck_subscriptions_interval
        CHECK (billing_interval IN ('MONTHLY', 'YEARLY'));

COMMIT;
