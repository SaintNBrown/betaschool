-- V1__initial_schema.sql
-- BetaSchool Full Schema - 3NF/BCNF Normalized

-- =====================================================
-- CORE ENTITIES
-- =====================================================

CREATE TABLE session (
    id           BIGSERIAL PRIMARY KEY,
    session_name VARCHAR(50)  NOT NULL UNIQUE,
    start_date   DATE         NOT NULL,
    closing_date DATE         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_session_dates CHECK (closing_date > start_date)
);

CREATE TABLE class (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(30) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE subject (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(100) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE student (
    id          BIGSERIAL PRIMARY KEY,
    surname     VARCHAR(100) NOT NULL,
    other_names VARCHAR(200) NOT NULL,
    email       VARCHAR(255) UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE teacher (
    id          BIGSERIAL PRIMARY KEY,
    surname     VARCHAR(100) NOT NULL,
    other_names VARCHAR(200) NOT NULL,
    email       VARCHAR(255) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- =====================================================
-- SESSION-SCOPED ENROLLMENT
-- =====================================================

CREATE TABLE class_session (
    id         BIGSERIAL PRIMARY KEY,
    class_id   BIGINT      NOT NULL REFERENCES class(id),
    session_id BIGINT      NOT NULL REFERENCES session(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (class_id, session_id)
);

CREATE TABLE student_class_enrollment (
    id               BIGSERIAL PRIMARY KEY,
    student_id       BIGINT      NOT NULL REFERENCES student(id),
    class_session_id BIGINT      NOT NULL REFERENCES class_session(id),
    enrolled_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (student_id, class_session_id)
);

CREATE TABLE teacher_class_assignment (
    id               BIGSERIAL PRIMARY KEY,
    teacher_id       BIGINT      NOT NULL REFERENCES teacher(id),
    class_session_id BIGINT      NOT NULL REFERENCES class_session(id),
    is_form_teacher  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (teacher_id, class_session_id)
);

-- =====================================================
-- SUBJECT ASSIGNMENT
-- =====================================================

CREATE TABLE class_subject (
    id               BIGSERIAL PRIMARY KEY,
    class_session_id BIGINT      NOT NULL REFERENCES class_session(id),
    subject_id       BIGINT      NOT NULL REFERENCES subject(id),
    is_elective      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (class_session_id, subject_id)
);

CREATE TABLE teacher_subject_assignment (
    id               BIGSERIAL PRIMARY KEY,
    teacher_id       BIGINT      NOT NULL REFERENCES teacher(id),
    class_subject_id BIGINT      NOT NULL REFERENCES class_subject(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (class_subject_id)  -- one teacher per subject per class per session
);

CREATE TABLE student_subject_enrollment (
    id               BIGSERIAL PRIMARY KEY,
    student_id       BIGINT      NOT NULL REFERENCES student(id),
    class_subject_id BIGINT      NOT NULL REFERENCES class_subject(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (student_id, class_subject_id)
);

-- =====================================================
-- TERM, EXAMINATION & RESULT
-- =====================================================

CREATE TABLE term (
    id               BIGSERIAL PRIMARY KEY,
    session_id       BIGINT      NOT NULL REFERENCES session(id),
    class_session_id BIGINT      NOT NULL REFERENCES class_session(id),
    term_number      INTEGER    NOT NULL CHECK (term_number BETWEEN 1 AND 3),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (class_session_id, term_number)
);

CREATE TABLE examination (
    id               BIGSERIAL PRIMARY KEY,
    term_id          BIGINT      NOT NULL REFERENCES term(id),
    class_subject_id BIGINT      NOT NULL REFERENCES class_subject(id),
    exam_date        DATE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (term_id, class_subject_id)
);

CREATE TABLE result (
    id             BIGSERIAL PRIMARY KEY,
    examination_id BIGINT         NOT NULL REFERENCES examination(id),
    student_id     BIGINT         NOT NULL REFERENCES student(id),
    score          DECIMAL(5,2)   CHECK (score BETWEEN 0 AND 100),
    grade          VARCHAR(2),
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    UNIQUE (examination_id, student_id)
);

-- =====================================================
-- INDEXES FOR QUERY PERFORMANCE
-- =====================================================

CREATE INDEX idx_class_session_session   ON class_session(session_id);
CREATE INDEX idx_class_session_class     ON class_session(class_id);

CREATE INDEX idx_sce_student             ON student_class_enrollment(student_id);
CREATE INDEX idx_sce_class_session       ON student_class_enrollment(class_session_id);

CREATE INDEX idx_tca_teacher             ON teacher_class_assignment(teacher_id);
CREATE INDEX idx_tca_class_session       ON teacher_class_assignment(class_session_id);

CREATE INDEX idx_cs_class_session        ON class_subject(class_session_id);
CREATE INDEX idx_cs_subject              ON class_subject(subject_id);
CREATE INDEX idx_cs_elective             ON class_subject(is_elective);

CREATE INDEX idx_tsa_teacher             ON teacher_subject_assignment(teacher_id);
CREATE INDEX idx_sse_student             ON student_subject_enrollment(student_id);
CREATE INDEX idx_sse_class_subject       ON student_subject_enrollment(class_subject_id);

CREATE INDEX idx_term_session            ON term(session_id);
CREATE INDEX idx_term_class_session      ON term(class_session_id);

CREATE INDEX idx_exam_term               ON examination(term_id);
CREATE INDEX idx_exam_class_subject      ON examination(class_subject_id);

CREATE INDEX idx_result_examination      ON result(examination_id);
CREATE INDEX idx_result_student          ON result(student_id);

-- =====================================================
-- UPDATED_AT TRIGGER
-- =====================================================

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_session_updated_at          BEFORE UPDATE ON session                  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_class_updated_at            BEFORE UPDATE ON class                    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_subject_updated_at          BEFORE UPDATE ON subject                  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_student_updated_at          BEFORE UPDATE ON student                  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_teacher_updated_at          BEFORE UPDATE ON teacher                  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_class_session_updated_at    BEFORE UPDATE ON class_session            FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_sce_updated_at              BEFORE UPDATE ON student_class_enrollment FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_tca_updated_at              BEFORE UPDATE ON teacher_class_assignment FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_class_subject_updated_at    BEFORE UPDATE ON class_subject            FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_tsa_updated_at              BEFORE UPDATE ON teacher_subject_assignment FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_sse_updated_at              BEFORE UPDATE ON student_subject_enrollment FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_term_updated_at             BEFORE UPDATE ON term                     FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_examination_updated_at      BEFORE UPDATE ON examination              FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
CREATE TRIGGER trg_result_updated_at           BEFORE UPDATE ON result                   FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
