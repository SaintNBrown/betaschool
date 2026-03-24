-- ─────────────────────────────────────────────────────────────────────────────
-- V10: Password reset tokens
-- One token per user at a time. Superseded tokens are invalidated on issue.
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE password_reset_token (
    id          BIGSERIAL       PRIMARY KEY,
    user_id     BIGINT          NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,

    -- Stored as a SHA-256 hex digest of the raw token sent in the email.
    -- The raw token is never persisted — only the hash is, to prevent
    -- database leakage from being exploited directly.
    token_hash  VARCHAR(64)     NOT NULL UNIQUE,

    expires_at  TIMESTAMPTZ     NOT NULL,
    used        BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_password_reset_user UNIQUE (user_id)
);

CREATE INDEX idx_password_reset_token_hash   ON password_reset_token (token_hash);
CREATE INDEX idx_password_reset_token_expiry ON password_reset_token (expires_at);

COMMENT ON TABLE password_reset_token IS
    'One active (unused, non-expired) reset token per user. '
    'Token hash is SHA-256 of the raw URL-safe token. Raw token is never stored.';
