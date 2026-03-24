-- V7__timetable.sql
-- Weekly timetable per class-session with versioning.
-- Each published timetable creates a new version; only one is active at a time.
-- Historical versions are capped at 5 per class-session (enforced in application layer).

CREATE TABLE timetable (
    id               BIGSERIAL    PRIMARY KEY,
    school_id        BIGINT       NOT NULL REFERENCES school(id),
    class_session_id BIGINT       NOT NULL REFERENCES class_session(id),
    version          INTEGER      NOT NULL DEFAULT 1,
    is_active        BOOLEAN      NOT NULL DEFAULT TRUE,
    notes            TEXT,                          -- optional admin note about this version
    created_by       BIGINT       REFERENCES app_user(id),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
    -- "only one active timetable per class-session" is enforced in the application layer
    -- via deactivateAllForClassSession() before every publish, not at DB level,
    -- because a partial unique index on is_active = TRUE would prevent multiple
    -- inactive (historical) versions from coexisting.
);

CREATE TABLE timetable_slot (
    id               BIGSERIAL    PRIMARY KEY,
    school_id        BIGINT       NOT NULL REFERENCES school(id),
    timetable_id     BIGINT       NOT NULL REFERENCES timetable(id) ON DELETE CASCADE,

    day_of_week      VARCHAR(10)  NOT NULL
                     CHECK (day_of_week IN ('MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY','SATURDAY','SUNDAY')),

    start_time       TIME         NOT NULL,
    end_time         TIME         NOT NULL,

    -- SUBJECT slots link to a class_subject (teacher resolved from teacher_subject_assignment)
    -- ACTIVITY slots carry a free-text label (devotion, break, drills, etc.)
    slot_type        VARCHAR(10)  NOT NULL CHECK (slot_type IN ('SUBJECT','ACTIVITY')),

    class_subject_id BIGINT       REFERENCES class_subject(id),  -- null for ACTIVITY
    activity_label   VARCHAR(100),                                -- null for SUBJECT

    sort_order       INTEGER      NOT NULL DEFAULT 0,  -- display order within the day

    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_slot_end_after_start CHECK (end_time > start_time),
    CONSTRAINT chk_slot_subject_xor_activity CHECK (
        (slot_type = 'SUBJECT'  AND class_subject_id IS NOT NULL AND activity_label IS NULL) OR
        (slot_type = 'ACTIVITY' AND activity_label IS NOT NULL   AND class_subject_id IS NULL)
    )
);

CREATE INDEX idx_timetable_class_session  ON timetable(class_session_id);
CREATE INDEX idx_timetable_school         ON timetable(school_id);

-- Enforce at most one active timetable per class-session at the DB level.
-- A partial unique index on is_active = TRUE allows unlimited inactive (historical) rows
-- while guaranteeing uniqueness only when is_active is true.
CREATE UNIQUE INDEX idx_timetable_one_active_per_class_session
    ON timetable (class_session_id)
    WHERE is_active = TRUE;
CREATE INDEX idx_timetable_slot_timetable ON timetable_slot(timetable_id);
CREATE INDEX idx_timetable_slot_day       ON timetable_slot(timetable_id, day_of_week);

-- Trigger to maintain updated_at on timetable
CREATE TRIGGER trg_timetable_updated_at
    BEFORE UPDATE ON timetable
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER trg_timetable_slot_updated_at
    BEFORE UPDATE ON timetable_slot
    FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
