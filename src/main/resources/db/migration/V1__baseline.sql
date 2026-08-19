-- Baseline migration: proves the Gradle -> Flyway -> Postgres pipeline
-- works end to end. No domain tables yet - those get added
-- incrementally as repository implementations are built (see
-- docs/DDD_Design.md Section 10), not as one big upfront migration.

CREATE TABLE schema_baseline (
    established_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO schema_baseline DEFAULT VALUES;
