-- ============================================================================
-- V21 — SaaS Billing, Entitlements, Audit & Regional Currencies
-- ============================================================================

-- 0. Safety snapshot before migration (retained after migration)
CREATE TABLE IF NOT EXISTS subscriptions_backup_v21 AS
SELECT * FROM subscriptions;

-- 1. Extend organizations with billing metadata (restricted to OWNER/ADMIN)
ALTER TABLE organizations
    ADD COLUMN IF NOT EXISTS billing_name    VARCHAR(255),
    ADD COLUMN IF NOT EXISTS billing_address VARCHAR(512),
    ADD COLUMN IF NOT EXISTS tax_id          VARCHAR(64);

-- 2. Extend subscriptions with provider, lifecycle, dunning & private beta fields
-- Note: trial_end is omitted as Vantis does not use paid trial states.
ALTER TABLE subscriptions
    ADD COLUMN IF NOT EXISTS provider                       VARCHAR(32) NOT NULL DEFAULT 'NOOP',
    ADD COLUMN IF NOT EXISTS cancel_at_period_end           BOOLEAN     NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS past_due_since                 TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS beta_started_at                TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS beta_ends_at                  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS founding_offer_eligible_until  TIMESTAMPTZ;

-- Allow billing_country to be NULL when unknown (fallback currency is USD)
ALTER TABLE subscriptions
    ALTER COLUMN billing_country DROP NOT NULL;

-- 2a. Normalize currencies and statuses, logging any rewritten currencies
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT id, workspace_id, currency
        FROM subscriptions
        WHERE currency IS NULL
           OR UPPER(TRIM(currency)) NOT IN ('USD', 'GBP', 'EUR', 'INR', 'CAD', 'AUD', 'SGD', 'JPY')
    LOOP
        RAISE NOTICE 'Normalizing invalid/missing currency "%" to "USD" for subscription id % (workspace %)',
            r.currency, r.id, r.workspace_id;
    END LOOP;
END $$;

UPDATE subscriptions
SET currency = UPPER(TRIM(currency))
WHERE currency IS NOT NULL;

UPDATE subscriptions
SET currency = 'USD'
WHERE currency IS NULL
   OR UPPER(TRIM(currency)) NOT IN ('USD', 'GBP', 'EUR', 'INR', 'CAD', 'AUD', 'SGD', 'JPY');

UPDATE subscriptions
SET status = 'ACTIVE'
WHERE status = 'TRIALING';

-- 2b. Recreate plan, status, provider, currency, and date integrity constraints
ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS ck_subscriptions_plan,
    ADD CONSTRAINT ck_subscriptions_plan
        CHECK (plan IN ('FREE', 'PRIVATE_BETA', 'PRO', 'BUSINESS'));

ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS ck_subscriptions_status,
    ADD CONSTRAINT ck_subscriptions_status
        CHECK (status IN ('ACTIVE', 'PAST_DUE', 'CANCELED', 'INCOMPLETE'));

ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS ck_subscriptions_provider,
    ADD CONSTRAINT ck_subscriptions_provider
        CHECK (provider IN ('NOOP', 'STRIPE', 'RAZORPAY'));

ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS ck_subscriptions_currency,
    ADD CONSTRAINT ck_subscriptions_currency
        CHECK (currency IN ('USD', 'GBP', 'EUR', 'INR', 'CAD', 'AUD', 'SGD', 'JPY'));

ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS ck_subscriptions_beta_period,
    ADD CONSTRAINT ck_subscriptions_beta_period
        CHECK (beta_ends_at IS NULL OR beta_started_at IS NULL OR beta_ends_at > beta_started_at);

CREATE INDEX IF NOT EXISTS ix_subscriptions_provider_sub
    ON subscriptions (provider_subscription_id);

CREATE INDEX IF NOT EXISTS ix_subscriptions_plan
    ON subscriptions (plan);

-- 3. Atomic per-period usage counters (period-scoped only)
CREATE TABLE IF NOT EXISTS usage_counters (
    id                  UUID         PRIMARY KEY,
    organization_id     UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    period_start        TIMESTAMPTZ  NOT NULL,
    period_end          TIMESTAMPTZ  NOT NULL,
    executions          BIGINT       NOT NULL DEFAULT 0 CHECK (executions >= 0),
    ai_generations      BIGINT       NOT NULL DEFAULT 0 CHECK (ai_generations >= 0),
    ai_investigations   BIGINT       NOT NULL DEFAULT 0 CHECK (ai_investigations >= 0),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_usage_counters_org_period UNIQUE (organization_id, period_start)
);

CREATE INDEX IF NOT EXISTS ix_usage_counters_org
    ON usage_counters (organization_id);

-- 4. Dedicated lifetime trial usage table (outside per-period billing cycles)
CREATE TABLE IF NOT EXISTS lifetime_usage_counters (
    organization_id                  UUID         PRIMARY KEY REFERENCES organizations (id) ON DELETE CASCADE,
    ai_investigations_trial_used     INTEGER      NOT NULL DEFAULT 0 CHECK (ai_investigations_trial_used >= 0),
    created_at                       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at                       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 5. Webhook events log for provider signature verification & idempotency
CREATE TABLE IF NOT EXISTS billing_events (
    id                  UUID         PRIMARY KEY,
    provider            VARCHAR(32)  NOT NULL,
    provider_event_id   VARCHAR(255) NOT NULL,
    event_type          VARCHAR(128) NOT NULL,
    payload             JSONB        NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    error_message       TEXT,
    processed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_billing_events_provider
        CHECK (provider IN ('STRIPE', 'RAZORPAY', 'NOOP')),
    CONSTRAINT ck_billing_events_status
        CHECK (status IN ('PENDING', 'PROCESSED', 'FAILED', 'IGNORED')),
    CONSTRAINT uq_billing_events_provider_event
        UNIQUE (provider, provider_event_id)
);

CREATE INDEX IF NOT EXISTS ix_billing_events_provider_status
    ON billing_events (provider, status);
CREATE INDEX IF NOT EXISTS ix_billing_events_created
    ON billing_events (created_at DESC);

-- 6. Customer Invoices (stored in minor units; JPY has 0 decimals)
-- ON DELETE RESTRICT preserves financial records from accidental tenant hard-deletion
CREATE TABLE IF NOT EXISTS invoices (
    id                   UUID         PRIMARY KEY,
    organization_id      UUID         NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    provider_invoice_id  VARCHAR(255) NOT NULL UNIQUE,
    provider             VARCHAR(32)  NOT NULL,
    amount_minor         BIGINT       NOT NULL CHECK (amount_minor >= 0),
    currency             VARCHAR(3)   NOT NULL,
    status               VARCHAR(32)  NOT NULL,
    hosted_url           VARCHAR(1024),
    period_start         TIMESTAMPTZ,
    period_end           TIMESTAMPTZ,
    paid_at              TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_invoices_provider
        CHECK (provider IN ('STRIPE', 'RAZORPAY', 'NOOP')),
    CONSTRAINT ck_invoices_currency
        CHECK (currency IN ('USD', 'GBP', 'EUR', 'INR', 'CAD', 'AUD', 'SGD', 'JPY')),
    CONSTRAINT ck_invoices_status
        CHECK (status IN ('DRAFT', 'OPEN', 'PAID', 'VOID', 'UNCOLLECTIBLE'))
);

CREATE INDEX IF NOT EXISTS ix_invoices_org
    ON invoices (organization_id, created_at DESC);

-- 7. Applied discounts and coupons (30% founding discount created at checkout)
CREATE TABLE IF NOT EXISTS billing_discounts (
    id                   UUID         PRIMARY KEY,
    organization_id      UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    code                 VARCHAR(64)  NOT NULL,
    discount_pct         INTEGER      NOT NULL CHECK (discount_pct >= 0 AND discount_pct <= 100),
    starts_at            TIMESTAMPTZ  NOT NULL,
    ends_at              TIMESTAMPTZ  NOT NULL,
    provider_coupon_id   VARCHAR(255),
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_billing_discounts_org_code UNIQUE (organization_id, code),
    CONSTRAINT ck_billing_discounts_period CHECK (ends_at > starts_at)
);

CREATE INDEX IF NOT EXISTS ix_billing_discounts_org
    ON billing_discounts (organization_id);

-- 8. Audit trail for plan changes, cancellations, and support overrides
-- ON DELETE RESTRICT ensures audit logs cannot be quietly pruned via tenant deletion
CREATE TABLE IF NOT EXISTS billing_audit_logs (
    id                   UUID         PRIMARY KEY,
    organization_id      UUID         NOT NULL REFERENCES organizations (id) ON DELETE RESTRICT,
    actor_user_id        UUID         REFERENCES users (id) ON DELETE SET NULL,
    action               VARCHAR(64)  NOT NULL,
    from_plan            VARCHAR(32),
    to_plan              VARCHAR(32),
    reason               TEXT,
    metadata             JSONB,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_billing_audit_org
    ON billing_audit_logs (organization_id, created_at DESC);

-- 9. Backfill existing workspaces into PRIVATE_BETA state
-- Only update rows without an active provider subscription and in FREE or PRIVATE_BETA.
UPDATE subscriptions
SET plan = 'PRIVATE_BETA',
    status = 'ACTIVE',
    beta_started_at = COALESCE(beta_started_at, now()),
    beta_ends_at = COALESCE(beta_ends_at, now() + INTERVAL '45 days'),
    founding_offer_eligible_until = COALESCE(founding_offer_eligible_until, now() + INTERVAL '45 days'),
    updated_at = now()
WHERE provider_subscription_id IS NULL
  AND plan IN ('FREE', 'PRIVATE_BETA');

-- Insert subscription rows for any organization missing one
INSERT INTO subscriptions (
    id,
    workspace_id,
    plan,
    status,
    currency,
    billing_country,
    billing_interval,
    current_period_start,
    current_period_end,
    provider,
    beta_started_at,
    beta_ends_at,
    founding_offer_eligible_until,
    created_at,
    updated_at
)
SELECT
    gen_random_uuid(),
    o.id,
    'PRIVATE_BETA',
    'ACTIVE',
    CASE
        WHEN UPPER(o.billing_country) = 'IN' THEN 'INR'
        WHEN UPPER(o.billing_country) = 'GB' THEN 'GBP'
        WHEN UPPER(o.billing_country) IN ('DE','FR','IT','ES','NL','IE','AT','BE','FI','PT','GR') THEN 'EUR'
        WHEN UPPER(o.billing_country) = 'CA' THEN 'CAD'
        WHEN UPPER(o.billing_country) = 'AU' THEN 'AUD'
        WHEN UPPER(o.billing_country) = 'SG' THEN 'SGD'
        WHEN UPPER(o.billing_country) = 'JP' THEN 'JPY'
        ELSE 'USD'
    END,
    o.billing_country,
    'MONTHLY',
    now(),
    now() + INTERVAL '30 days',
    'NOOP',
    now(),
    now() + INTERVAL '45 days',
    now() + INTERVAL '45 days',
    now(),
    now()
FROM organizations o
WHERE NOT EXISTS (
    SELECT 1 FROM subscriptions s WHERE s.workspace_id = o.id
);
