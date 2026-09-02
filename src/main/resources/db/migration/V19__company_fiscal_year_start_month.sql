-- "The fiscal year has to be set during Tenant onboarding" (2026-09-02).
-- Existing rows default to 1 (January/calendar year) - the same default
-- Company.create() itself uses at the domain-constructor level for
-- callers that don't care about fiscal timing.
ALTER TABLE companies ADD COLUMN fiscal_year_start_month INTEGER NOT NULL DEFAULT 1;
