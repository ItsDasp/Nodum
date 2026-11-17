ALTER TABLE modules
    ADD COLUMN provider_id VARCHAR(100),
    ADD COLUMN provider_version VARCHAR(50),
    ADD COLUMN configuration_json TEXT NOT NULL DEFAULT '{}',
    ADD COLUMN lifecycle_state VARCHAR(30) NOT NULL DEFAULT 'CONFIGURED',
    ADD COLUMN lifecycle_error TEXT;

CREATE INDEX idx_modules_provider_state ON modules(provider_id, lifecycle_state);
