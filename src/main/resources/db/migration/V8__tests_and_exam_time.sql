-- V8__tests_and_exam_time.sql
-- 1. Add exam start time and duration to examination
-- 2. Add test score table (continuous assessment scores before examination)
-- 3. Score weight configuration per examination (test max + exam max summing to 100)

-- ── Add time fields to examination ────────────────────────────────────────
ALTER TABLE examination ADD COLUMN exam_start_time TIME;
ALTER TABLE examination ADD COLUMN duration_minutes INTEGER;

-- ── Continuous Assessment (Test) scores ────────────────────────────────────
-- Each examination can have multiple test/CA scores recorded before the exam.
-- The exam itself is one score. Together they sum to 100 using weights defined
-- on the examination (test_max_score + exam_max_score = 100).
--
-- exam.test_max_score  e.g. 40 means tests contribute up to 40 marks
-- exam.exam_max_score  e.g. 60 means the exam contributes up to 60 marks
ALTER TABLE examination ADD COLUMN test_max_score  DECIMAL(5,2) NOT NULL DEFAULT 40;
ALTER TABLE examination ADD COLUMN exam_max_score  DECIMAL(5,2) NOT NULL DEFAULT 60;

-- Validate weights sum to exactly 100
ALTER TABLE examination ADD CONSTRAINT chk_exam_weights_sum_100
    CHECK (test_max_score + exam_max_score = 100);

-- ── test_score: one row per student per examination for their CA/test score ──
CREATE TABLE test_score (
    id             BIGSERIAL PRIMARY KEY,
    school_id      BIGINT         NOT NULL REFERENCES school(id),
    examination_id BIGINT         NOT NULL REFERENCES examination(id),
    student_id     BIGINT         NOT NULL REFERENCES student(id),
    score          DECIMAL(5,2)   NOT NULL CHECK (score >= 0),
    notes          VARCHAR(200),  -- optional label: "Test 1", "CA", "Mid-term test"
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    UNIQUE (examination_id, student_id)
);

CREATE INDEX idx_test_score_examination ON test_score(examination_id);
CREATE INDEX idx_test_score_student     ON test_score(student_id);

CREATE TRIGGER trg_test_score_updated_at
    BEFORE UPDATE ON test_score
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- ── Update result to store exam_score separately from combined total ────────
-- result.score will now store the EXAM component only (up to exam_max_score).
-- The combined total (test + exam) is computed at query time.
-- Add a column to make the split explicit.
ALTER TABLE result ADD COLUMN exam_score   DECIMAL(5,2);
ALTER TABLE result ADD COLUMN combined_score DECIMAL(5,2) GENERATED ALWAYS AS
    (exam_score) STORED;  -- placeholder; real combined computed in app layer

-- Remove the generated column — combined score is computed in the query layer
-- not stored, to avoid staleness when test scores are updated.
ALTER TABLE result DROP COLUMN combined_score;

-- result.score retains its original meaning as the exam component.
-- No data migration needed — existing scores stay as exam scores.
