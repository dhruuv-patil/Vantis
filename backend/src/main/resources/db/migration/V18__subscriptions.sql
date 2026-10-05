-- Vantis — Subscriptions & Billing Table
-- Supports localized pricing, currency stability, and plan transitions.

ALTER TABLE users ADD COLUMN IF NOT EXISTS billing_country varchar(2);
ALTER TABLE organizations ADD COLUMN IF NOT EXISTS billing_country varchar(2);

CREATE TABLE IF NOT EXISTS subscriptions (
    id                      uuid         PRIMARY KEY,
    workspace_id            uuid         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    plan                    varchar(32)  NOT NULL,   -- 'FREE', 'PRIVATE_BETA', 'PRO', 'BUSINESS'
    status                  varchar(32)  NOT NULL,   -- 'ACTIVE', 'TRIALING', 'PAST_DUE', 'CANCELED', 'INCOMPLETE'
    currency                varchar(3)   NOT NULL,   -- ISO 4217 uppercase, e.g. 'USD', 'INR', 'EUR'
    billing_country         varchar(2)   NOT NULL,   -- ISO 3166-1 alpha-2, e.g. 'US', 'IN', 'DE'
    billing_interval        varchar(16)  NOT NULL,   -- 'MONTHLY', 'YEARLY'
    current_period_start    timestamptz  NOT NULL,
    current_period_end      timestamptz  NOT NULL,
    provider_customer_id    varchar(255),
    provider_subscription_id varchar(255),
    created_at              timestamptz  NOT NULL,
    updated_at              timestamptz  NOT NULL,
    CONSTRAINT uq_subscriptions_workspace UNIQUE (workspace_id),
    CONSTRAINT ck_subscriptions_plan CHECK (plan IN ('FREE', 'PRIVATE_BETA', 'PRO', 'BUSINESS')),
    CONSTRAINT ck_subscriptions_status CHECK (status IN ('ACTIVE', 'TRIALING', 'PAST_DUE', 'CANCELED', 'INCOMPLETE')),
    CONSTRAINT ck_subscriptions_interval CHECK (billing_interval IN ('MONTHLY', 'YEARLY'))
);

CREATE INDEX IF NOT EXISTS ix_subscriptions_workspace_id ON subscriptions (workspace_id);
CREATE INDEX IF NOT EXISTS ix_subscriptions_status ON subscriptions (status);
CREATE INDEX IF NOT EXISTS ix_subscriptions_provider_customer ON subscriptions (provider_customer_id);
