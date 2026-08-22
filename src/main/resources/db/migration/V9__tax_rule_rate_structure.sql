-- Generalizes tax_rules.rate (a single flat NUMERIC) into rate_structure
-- (a hand-encoded TEXT column, same convention as journal_lines.dimensions -
-- see infrastructure/persistence/dimension_encoding.kt's own KDoc for why
-- that's plain TEXT, not jsonb; rate_structure_encoding.kt mirrors it).
-- Real jurisdictions documented in docs/UK, IE, NG, SL, LR, GN, CI
-- (2026-08-22) need genuinely different Corporate Income Tax computation
-- shapes - marginal-relief tiering, category splits (trading/passive,
-- sector, residency), threshold exemptions - not just different flat
-- rates, which TaxRule's original single `rate` column couldn't represent.

ALTER TABLE tax_rules ADD COLUMN rate_structure TEXT;

-- Preserve existing rows: every TaxRule created before this migration is a
-- flat rate by construction (RateStructure.Flat's encoding is "FLAT:<rate>").
UPDATE tax_rules SET rate_structure = 'FLAT:' || rate::text;

ALTER TABLE tax_rules ALTER COLUMN rate_structure SET NOT NULL;
ALTER TABLE tax_rules DROP COLUMN rate;
