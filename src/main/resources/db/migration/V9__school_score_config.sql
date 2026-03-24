-- ─────────────────────────────────────────────────────────────────────────────
-- V9: School-level score configuration
--   • score_ratio  — CA (test) vs exam weight; stored as two integers summing to 100
--   • grading_band — per-school grade boundaries (A/B/C/D/F or custom labels)
-- ─────────────────────────────────────────────────────────────────────────────

CREATE TABLE school_score_config (
    id                  BIGSERIAL       PRIMARY KEY,
    school_id           BIGINT          NOT NULL UNIQUE REFERENCES school(id),

    -- CA : Exam ratio  (e.g. ca_weight=40, exam_weight=60 → 40:60)
    -- Both values must sum to 100; enforced by CHECK + application layer.
    ca_weight           INT             NOT NULL DEFAULT 40,
    exam_weight         INT             NOT NULL DEFAULT 60,

    -- Grading bands stored as JSON array of objects:
    --   [{"grade":"A","minScore":70,"maxScore":100}, ...]
    -- Application layer owns the validation and ordering.
    grading_bands       JSONB           NOT NULL DEFAULT
        '[
            {"grade":"A","minScore":70,"maxScore":100},
            {"grade":"B","minScore":60,"maxScore":69},
            {"grade":"C","minScore":50,"maxScore":59},
            {"grade":"D","minScore":45,"maxScore":49},
            {"grade":"F","minScore":0,"maxScore":44}
        ]'::jsonb,

    created_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_score_config_weights CHECK (ca_weight + exam_weight = 100),
    CONSTRAINT chk_score_config_ca_pos  CHECK (ca_weight  >= 0),
    CONSTRAINT chk_score_config_ex_pos  CHECK (exam_weight >= 0)
);

-- Seed one default config row for every school that already exists
INSERT INTO school_score_config (school_id)
SELECT id FROM school
ON CONFLICT (school_id) DO NOTHING;

COMMENT ON TABLE school_score_config IS
  'Per-school configuration for CA/exam score ratio and grading band thresholds';
