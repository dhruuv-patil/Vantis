-- Trace.run — OAuth2 Accounts and Multi-Provider Identity
-- Allows users to authenticate via Google and GitHub OAuth2 alongside email/password.

-- Allow password_hash to be nullable for OAuth-only users
ALTER TABLE users ALTER COLUMN password_hash DROP NOT NULL;

CREATE TABLE oauth_accounts (
    id               uuid         PRIMARY KEY,
    user_id          uuid         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider         varchar(32)  NOT NULL,   -- 'google', 'github'
    provider_user_id varchar(255) NOT NULL,   -- Provider's unique user ID (Google sub, GitHub id)
    email            varchar(255),             -- Verified email from provider at link time
    created_at       timestamptz  NOT NULL,
    updated_at       timestamptz  NOT NULL,
    CONSTRAINT uq_oauth_accounts_provider_user UNIQUE (provider, provider_user_id),
    CONSTRAINT uq_oauth_accounts_user_provider UNIQUE (user_id, provider)
);

CREATE INDEX ix_oauth_accounts_user_id ON oauth_accounts (user_id);
CREATE INDEX ix_oauth_accounts_provider_user ON oauth_accounts (provider, provider_user_id);
