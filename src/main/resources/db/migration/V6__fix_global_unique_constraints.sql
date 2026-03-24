-- V6__fix_global_unique_constraints.sql
-- =========================================================
-- Three global UNIQUE constraints survived from V1 that were
-- never scoped to school_id.  They cause false conflicts when
-- two different schools use the same session name, teacher
-- email, or student email.
-- =========================================================

-- ── 1. session.session_name ───────────────────────────────
-- V1 created: session_name VARCHAR(50) NOT NULL UNIQUE
-- V2 added:   UNIQUE (school_id, session_name)  as uq_session_name_per_school
-- The original unnamed global UNIQUE (created as session_session_name_key by
-- PostgreSQL) was never dropped.  Drop it now.
ALTER TABLE session DROP CONSTRAINT IF EXISTS session_session_name_key;

-- ── 2. teacher.email ──────────────────────────────────────
-- V1 created: email VARCHAR(255) NOT NULL UNIQUE  (global — wrong)
-- Teachers at different schools should be able to share an email address.
-- Uniqueness should be per school only.
ALTER TABLE teacher DROP CONSTRAINT IF EXISTS teacher_email_key;
ALTER TABLE teacher ADD CONSTRAINT uq_teacher_email_per_school UNIQUE (school_id, email);

-- ── 3. student.email ──────────────────────────────────────
-- V1 created: email VARCHAR(255) UNIQUE  (global — wrong)
-- Same fix as teacher.
ALTER TABLE student DROP CONSTRAINT IF EXISTS student_email_key;
-- student.email is nullable (email is optional for students), so the constraint
-- must use a partial index to allow multiple NULLs while still enforcing
-- uniqueness on non-null values within the same school.
ALTER TABLE student ADD CONSTRAINT uq_student_email_per_school UNIQUE (school_id, email);
