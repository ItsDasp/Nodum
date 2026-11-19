ALTER TABLE modules
    ADD COLUMN desired_state VARCHAR(30) NOT NULL DEFAULT 'STOPPED',
    ADD COLUMN observed_state VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN',
    ADD COLUMN runtime_id VARCHAR(255),
    ADD COLUMN runtime_provider VARCHAR(100),
    ADD COLUMN runtime_error TEXT,
    ADD COLUMN last_observed_at TIMESTAMP,
    ADD COLUMN restart_count INTEGER NOT NULL DEFAULT 0;

UPDATE modules
SET desired_state = CASE
        WHEN lifecycle_state = 'RUNNING' THEN 'RUNNING'
        ELSE 'STOPPED'
    END,
    observed_state = CASE
        WHEN lifecycle_state = 'RUNNING' THEN 'UNKNOWN'
        WHEN lifecycle_state = 'DEGRADED' THEN 'DEGRADED'
        WHEN lifecycle_state = 'ERROR' THEN 'ERROR'
        ELSE 'STOPPED'
    END;

CREATE INDEX idx_modules_desired_observed_state
    ON modules(desired_state, observed_state);
