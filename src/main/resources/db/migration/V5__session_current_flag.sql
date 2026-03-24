-- V5__session_current_flag.sql
-- Issue 5: Add is_current flag to session so schools can designate the active session.
-- Also adds the user_audit_log entity mapping that was already in V3 DDL but needed
-- the is_current column for session entity mapping to validate.

-- ── Add is_current to session ─────────────────────────────────────────────
ALTER TABLE session ADD COLUMN is_current BOOLEAN NOT NULL DEFAULT FALSE;

-- Enforce at most one current session per school using a partial unique index.
-- Only one row per school_id where is_current = true is permitted.
CREATE UNIQUE INDEX idx_session_one_current_per_school
    ON session (school_id)
    WHERE is_current = TRUE;
