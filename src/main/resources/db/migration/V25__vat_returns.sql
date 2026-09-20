-- VAT Return persistence (docs/IE/IE_VAT_MVP_Design.md #5, 2026-09-19) -
-- the same "persisted, not ephemeral" treatment tax_computations already
-- gets, and for the identical reason: a filed VAT figure must be
-- preserved as it was actually computed, not silently replaced by a
-- later recomputation once more entries post into the same window.
--
-- Only the top-line net figure is persisted here - output_vat/input_vat/
-- net_vat_due/direction, the audit-critical "what was filed" numbers.
-- The per-VatCategory breakdown (VatReturn.categoryBreakdown in code) is
-- deliberately NOT persisted: it's always re-derivable on demand from
-- the same permanently-posted, DimensionType.VAT_CATEGORY-tagged
-- journal_lines this return was computed from, so storing a second copy
-- would just be a cache that could drift, not a fact that needs
-- preserving the way the net figure does.
--
-- filing_period_start_date/end_date are a plain date range (VatFilingPeriod
-- in code), deliberately independent of periods(id) - a VAT filing window
-- and a Company's own accounting Period cadence are two different things
-- (see VatFilingPeriod's own KDoc).
CREATE TABLE vat_returns (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    filing_period_start_date DATE NOT NULL,
    filing_period_end_date DATE NOT NULL,
    vat_control_account_id UUID NOT NULL REFERENCES accounts(id),
    output_vat_amount NUMERIC(19, 4) NOT NULL,
    input_vat_amount NUMERIC(19, 4) NOT NULL,
    net_vat_due_amount NUMERIC(19, 4) NOT NULL,
    direction VARCHAR(20) NOT NULL,
    currency CHAR(3) NOT NULL,
    computed_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_vat_returns_company_id ON vat_returns(company_id);
