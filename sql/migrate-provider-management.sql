-- Existing MySQL databases only: execute once if Hibernate ddl-auto=update is disabled.
-- Adds nullable management fields and a version default; preserves existing provider rows.
ALTER TABLE relay_provider
    ADD COLUMN bic VARCHAR(11) NULL,
 ADD COLUMN nostro_account VARCHAR(64) NULL,
 ADD COLUMN gl_code VARCHAR(64) NULL,
 ADD COLUMN relationship_owner VARCHAR(64) NULL,
 ADD COLUMN appetite_review_date DATE NULL,
 ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
