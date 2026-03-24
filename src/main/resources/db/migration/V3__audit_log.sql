-- V3__audit_log.sql
-- Audit trail for school status changes and sensitive operations

CREATE TABLE school_audit_log (
    id           BIGSERIAL PRIMARY KEY,
    school_id    BIGINT      NOT NULL REFERENCES school(id),
    action       VARCHAR(50) NOT NULL,   -- REGISTERED, SUSPENDED, REACTIVATED, DEACTIVATED
    performed_by BIGINT      REFERENCES app_user(id),   -- system admin user id
    reason       TEXT,
    previous_status VARCHAR(20),
    new_status      VARCHAR(20),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_school ON school_audit_log(school_id);
CREATE INDEX idx_audit_action ON school_audit_log(action);
CREATE INDEX idx_audit_created ON school_audit_log(created_at);

-- General user activity audit (login, password change, etc.)
CREATE TABLE user_audit_log (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES app_user(id),
    school_id  BIGINT      REFERENCES school(id),
    action     VARCHAR(50) NOT NULL,   -- LOGIN, LOGOUT, PASSWORD_CHANGE, DEACTIVATED
    ip_address VARCHAR(45),
    user_agent TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_audit_user    ON user_audit_log(user_id);
CREATE INDEX idx_user_audit_school  ON user_audit_log(school_id);
CREATE INDEX idx_user_audit_created ON user_audit_log(created_at);
