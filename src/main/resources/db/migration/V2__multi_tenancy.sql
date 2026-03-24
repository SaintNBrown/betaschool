-- V2__multi_tenancy.sql
-- Multi-tenant school support

-- =====================================================
-- SCHOOL (TENANT)
-- =====================================================


CREATE TABLE school (
    id                   BIGSERIAL PRIMARY KEY,
    name                 VARCHAR(200)   NOT NULL,
    slug                 VARCHAR(100)   NOT NULL UNIQUE,
    email                VARCHAR(255)   NOT NULL,
    phone                VARCHAR(30),
    address              TEXT,
    status               VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    activated_at         TIMESTAMPTZ,
    deactivated_at       TIMESTAMPTZ,
    deactivation_reason  TEXT,
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- =====================================================
-- APP USER
-- =====================================================

CREATE TABLE app_user (
    id             BIGSERIAL PRIMARY KEY,
    school_id      BIGINT       REFERENCES school(id),     -- NULL for SYSTEM_ADMIN
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(30)  NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at  TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_school_required CHECK (
        (role = 'SYSTEM_ADMIN' AND school_id IS NULL) OR
        (role != 'SYSTEM_ADMIN' AND school_id IS NOT NULL)
    )
);

-- Links an app_user to a student or teacher profile
CREATE TABLE user_profile (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      BIGINT      NOT NULL UNIQUE REFERENCES app_user(id),
    profile_type VARCHAR(20) NOT NULL,
    profile_id   BIGINT      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- =====================================================
-- ADD school_id TO EVERY TENANT TABLE
-- =====================================================

ALTER TABLE session                   ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE class                     ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE subject                   ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE student                   ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE teacher                   ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE class_session             ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE student_class_enrollment  ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE teacher_class_assignment  ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE class_subject             ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE teacher_subject_assignment ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE student_subject_enrollment ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE term                      ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE examination               ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);
ALTER TABLE result                    ADD COLUMN school_id BIGINT NOT NULL REFERENCES school(id);

-- Drop old unique constraints that didn't include school_id, replace with tenant-aware ones
ALTER TABLE class   DROP CONSTRAINT class_name_key;
ALTER TABLE subject DROP CONSTRAINT subject_name_key;

ALTER TABLE class   ADD CONSTRAINT uq_class_name_per_school   UNIQUE (school_id, name);
ALTER TABLE subject ADD CONSTRAINT uq_subject_name_per_school UNIQUE (school_id, name);
ALTER TABLE session ADD CONSTRAINT uq_session_name_per_school UNIQUE (school_id, session_name);

-- =====================================================
-- INDEXES
-- =====================================================

CREATE INDEX idx_school_status   ON school(status);
CREATE INDEX idx_school_slug     ON school(slug);

CREATE INDEX idx_app_user_school ON app_user(school_id);
CREATE INDEX idx_app_user_role   ON app_user(role);
CREATE INDEX idx_app_user_email  ON app_user(email);

CREATE INDEX idx_session_school          ON session(school_id);
CREATE INDEX idx_class_school            ON class(school_id);
CREATE INDEX idx_subject_school          ON subject(school_id);
CREATE INDEX idx_student_school          ON student(school_id);
CREATE INDEX idx_teacher_school          ON teacher(school_id);
CREATE INDEX idx_class_session_school    ON class_session(school_id);
CREATE INDEX idx_result_school           ON result(school_id);

-- =====================================================
-- TRIGGERS
-- =====================================================

CREATE TRIGGER trg_school_updated_at   BEFORE UPDATE ON school   FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_appuser_updated_at  BEFORE UPDATE ON app_user FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =====================================================
-- SEED: Default system admin
-- Password: '@Admin12345' bcrypt-hashed (replace before production)
-- =====================================================

-- Default system admin: admin@betaschool.io
-- BCrypt cost 12 hash for '@Admin12345' — CHANGE BEFORE PRODUCTION
INSERT INTO app_user (school_id, email, password_hash, role, status)
VALUES (NULL, 'admin@betaschool.io',
        '$2a$10$KM0J46T7gRLimCRqE74i5u5.R6T2npFQpmI3X58wY3Al/MFK4E0d2',
        'SYSTEM_ADMIN', 'ACTIVE');
