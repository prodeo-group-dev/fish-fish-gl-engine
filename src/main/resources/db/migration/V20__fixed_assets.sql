-- Persists FixedAsset (docs/DDD_Design.md Section 2.8) - built
-- 2026-08-12/2026-08-14 as a domain-only aggregate with no table, HTTP
-- layer, or WEB screen of its own until now. Same shape as
-- V4__ecosystem_tables.sql's creditors/customers tables: a subsidiary
-- ledger entry alongside the Fixed Assets control account (code 1200),
-- not itself part of the posted journal_entries/journal_lines data.

CREATE TABLE fixed_assets (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    name VARCHAR(255) NOT NULL,
    category VARCHAR(20) NOT NULL,
    cost_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    acquisition_date DATE NOT NULL,
    useful_life_years INT,
    accumulated_depreciation_amount NUMERIC(19, 4) NOT NULL,
    accumulated_impairment_amount NUMERIC(19, 4) NOT NULL,
    is_disposed BOOLEAN NOT NULL
);

CREATE INDEX idx_fixed_assets_company_id ON fixed_assets(company_id);
