-- VAT rate table (2026-10-06, direct decision "Option B": VAT rates are
-- reference DATA, not code constants - a new country or a rate change is
-- an INSERT with no deploy). Replaces VatRateSchedule.IRELAND / .UK /
-- forJurisdiction, same direction as the jurisdiction registry (V29).
--
-- One row per (jurisdiction, category, effective_from): the rate in force
-- for a category is the latest row not after the sale date (minimal
-- effective-dating, docs/IE/IE_VAT_MVP_Design.md Decision 5). EXEMPT has
-- no rate by definition and is never stored; it is always available.
--
-- `verified` is the governance gate: only verified rows form a usable
-- schedule. A jurisdiction seeded with verified = FALSE posts nothing
-- (record-sale answers 409 no_vat_rate_schedule, the categories route says
-- "not configured") until its rates are confirmed against a primary tax
-- source and the rows are flipped to TRUE (an UPDATE, no deploy).
--
-- No FK to jurisdictions: rows for a jurisdiction can exist before it is
-- enabled for onboarding, and disabling one must not orphan its history.
-- `2000-01-01` is the placeholder "always in force" start date used for
-- rates with no known change date in this project's research.
CREATE TABLE vat_rates (
    jurisdiction CHAR(2) NOT NULL,
    category VARCHAR(30) NOT NULL,
    rate NUMERIC(8, 6) NOT NULL CHECK (rate >= 0),
    effective_from DATE NOT NULL,
    verified BOOLEAN NOT NULL,
    PRIMARY KEY (jurisdiction, category, effective_from)
);

-- Ireland (docs/IE/IE_Tax_And_Currency_Settings.md) - five bands; the
-- already-legislated 1 Jul 2026 change takes SECOND_REDUCED from 13.5% to 9%.
INSERT INTO vat_rates (jurisdiction, category, rate, effective_from, verified) VALUES
    ('IE', 'STANDARD',       0.230000, '2000-01-01', TRUE),
    ('IE', 'REDUCED',        0.135000, '2000-01-01', TRUE),
    ('IE', 'SECOND_REDUCED', 0.135000, '2000-01-01', TRUE),
    ('IE', 'SECOND_REDUCED', 0.090000, '2026-07-01', TRUE),
    ('IE', 'SUPER_REDUCED',  0.048000, '2000-01-01', TRUE),
    ('IE', 'ZERO_RATED',     0.000000, '2000-01-01', TRUE);

-- United Kingdom (docs/UK/UK_Tax_And_Currency_Settings.md) - three bands,
-- genuinely fewer than Ireland's: no SECOND_REDUCED / SUPER_REDUCED.
INSERT INTO vat_rates (jurisdiction, category, rate, effective_from, verified) VALUES
    ('UK', 'STANDARD',   0.200000, '2000-01-01', TRUE),
    ('UK', 'REDUCED',    0.050000, '2000-01-01', TRUE),
    ('UK', 'ZERO_RATED', 0.000000, '2000-01-01', TRUE);

-- Sierra Leone (docs/SL/SL_Tax_And_Currency_Settings.md, section 3) - GST,
-- a single flat 15% (in force since 2009; 2000-01-01 is the placeholder
-- start). SEEDED BUT NOT VERIFIED: the doc itself says to double-check
-- "no zero-rating or exemptions" against a primary NRA source before
-- treating the category set as confirmed. Not live until Femi confirms and
-- this row is flipped to verified = TRUE.
INSERT INTO vat_rates (jurisdiction, category, rate, effective_from, verified) VALUES
    ('SL', 'STANDARD', 0.150000, '2000-01-01', FALSE);
