-- Tax context tables (docs/DDD_Design.md Section 10.11) - TaxRule,
-- TaxComputation.

-- Global reference data, deliberately NOT scoped to any tenant_id/
-- company_id - confirmed with the user before building: a jurisdiction's
-- statutory tax rate is the same for every Company operating there,
-- regardless of which Tenant it belongs to, so it isn't owned by any one
-- of them. UNIQUE(jurisdiction, tax_type) enforces the domain's own
-- working assumption (see TaxRuleRepository.findByJurisdictionAndTaxType's
-- KDoc) that only one TaxRule is configured per jurisdiction/type
-- combination at a time - no effective-dating/rate-history model exists
-- yet, a known, flagged limitation, not silently allowed to go
-- ambiguous in the data itself.
CREATE TABLE tax_rules (
    id UUID PRIMARY KEY,
    jurisdiction VARCHAR(100) NOT NULL,
    tax_type VARCHAR(30) NOT NULL,
    rate NUMERIC(19, 4) NOT NULL,
    UNIQUE (jurisdiction, tax_type)
);

-- Unlike TrialBalance/ProfitAndLoss/WorkingCapital (ephemeral, no table
-- at all), TaxComputation is persisted for audit/compliance purposes - a
-- government-ready figure needs to be preserved as it was actually
-- computed, not silently replaced by a later recomputation. Real FKs
-- throughout: companies/periods are both persisted well before this
-- migration (V3/V2), and tax_rules is created earlier in this same file.
CREATE TABLE tax_computations (
    id UUID PRIMARY KEY,
    company_id UUID NOT NULL REFERENCES companies(id),
    period_id UUID NOT NULL REFERENCES periods(id),
    tax_rule_id UUID NOT NULL REFERENCES tax_rules(id),
    taxable_profit_amount NUMERIC(19, 4) NOT NULL,
    tax_due_amount NUMERIC(19, 4) NOT NULL,
    currency CHAR(3) NOT NULL,
    computed_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_tax_computations_company_id ON tax_computations(company_id);
