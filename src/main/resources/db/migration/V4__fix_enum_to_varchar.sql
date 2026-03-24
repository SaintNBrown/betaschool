-- V4__fix_enum_to_varchar.sql
-- Safely converts all custom PostgreSQL ENUM columns to VARCHAR.
--
-- Order matters:
--   1. Drop CHECK constraints that reference enum-typed columns
--   2. Drop column DEFAULTs that are enum-typed
--   3. ALTER each column TYPE with explicit USING cast
--   4. Restore DEFAULTs as plain string literals
--   5. Restore CHECK constraints
--   6. DROP the now-unused custom ENUM types

-- ── Step 1: Drop CHECK constraint that references role (user_role) ────────
ALTER TABLE app_user DROP CONSTRAINT IF EXISTS chk_school_required;

-- ── Step 2: Drop typed DEFAULTs before altering ───────────────────────────
ALTER TABLE school    ALTER COLUMN status      DROP DEFAULT;
ALTER TABLE app_user  ALTER COLUMN status      DROP DEFAULT;

-- ── Step 3: ALTER column types with explicit text cast ────────────────────
ALTER TABLE school
    ALTER COLUMN status      TYPE VARCHAR(20)  USING status::text;

ALTER TABLE app_user
    ALTER COLUMN role        TYPE VARCHAR(30)  USING role::text,
    ALTER COLUMN status      TYPE VARCHAR(20)  USING status::text;

ALTER TABLE user_profile
    ALTER COLUMN profile_type TYPE VARCHAR(20) USING profile_type::text;

-- ── Step 4: Restore string DEFAULTs ──────────────────────────────────────
ALTER TABLE school    ALTER COLUMN status SET DEFAULT 'ACTIVE';
ALTER TABLE app_user  ALTER COLUMN status SET DEFAULT 'ACTIVE';

-- ── Step 5: Restore CHECK constraint (now against plain VARCHAR) ──────────
ALTER TABLE app_user ADD CONSTRAINT chk_school_required CHECK (
    (role = 'SYSTEM_ADMIN' AND school_id IS NULL) OR
    (role != 'SYSTEM_ADMIN' AND school_id IS NOT NULL)
);

-- ── Step 6: Drop the now-unused custom ENUM types ─────────────────────────
DROP TYPE IF EXISTS school_status;
DROP TYPE IF EXISTS user_role;
DROP TYPE IF EXISTS user_status;
DROP TYPE IF EXISTS profile_type;
