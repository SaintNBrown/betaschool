-- ─────────────────────────────────────────────────────────────────────────────
-- V11: Report card display preference
--
-- Adds show_student_position to school_score_config.
--
-- When TRUE  → report card shows the student's class position + average.
-- When FALSE → report card shows the student's term average percentage
--              and a term grade derived from the school's grading bands.
--
-- Defaults to TRUE so existing schools keep their current behaviour.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE school_score_config
    ADD COLUMN show_student_position BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN school_score_config.show_student_position IS
    'TRUE  = report card displays class position (rank) + average. '
    'FALSE = report card displays term average percentage + term grade only.';
