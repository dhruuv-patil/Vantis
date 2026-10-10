-- Vantis — Email verification and signup authentication
-- Enforces email verification before access is granted.

-- 1. Add email_verified flag to users
ALTER TABLE users ADD COLUMN email_verified boolean NOT NULL DEFAULT false;

-- 2. Mark existing accounts that authenticated via OAuth as verified, since the
-- external OAuth provider (Google, GitHub) has already verified their email.
UPDATE users SET email_verified = true WHERE id IN (SELECT user_id FROM oauth_accounts);

-- 3. Email verification tokens (single-use, expiring). Only the SHA-256 hash is stored.
CREATE TABLE email_verification_tokens (
    id              uuid        PRIMARY KEY,
    user_id         uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash      varchar(64) NOT NULL,
    expires_at      timestamptz NOT NULL,
    created_at      timestamptz NOT NULL,
    used_at         timestamptz,
    CONSTRAINT uq_email_verification_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_email_verification_tokens_user ON email_verification_tokens (user_id);
