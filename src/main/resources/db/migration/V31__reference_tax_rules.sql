-- Reference tax rules as data (UAT 2026-10-08 v2.2, W-H1). No tax_rules row existed in any
-- environment, so POST /companies/{id}/tax answered no_tax_rule for every Company. Statutory
-- Corporate Income Tax is global reference data (TaxRule's own KDoc), seeded here the same way
-- jurisdictions (V29) and VAT rates (V30) are. Adding or changing a rule is an INSERT/UPDATE.
--
-- rate_structure uses the TEXT encoding in rate_structure_encoding.kt:
--   FLAT:<rate>
--   TIERED:<upperBound|rate>,<-|rate>;RELIEF:<lowerLimit>|<upperLimit>|<fraction>
--
-- UK (docs/UK/UK_Tax_And_Currency_Settings.md, 2026/27): 19% small profits up to 50,000, 25% main
-- rate from 250,000, marginal relief between at the standard fraction 3/200 = 0.015. Known limits,
-- unchanged from TaxRule's own notes: no associated-companies divisor and no effective dating.
-- Ireland (docs/IE/IE_Tax_And_Currency_Settings.md): 12.5% on trading income, 25% on passive income.
-- A CategorySplit rule: the compute request must name the category, TRADING or PASSIVE.
-- Sierra Leone (docs/SL/SL_Tax_And_Currency_Settings.md): flat 30% Corporate Income Tax.
--
-- ON CONFLICT DO NOTHING: a rule someone already configured by hand is never overwritten.
-- Other jurisdictions (NG, LR, GN, CI) are not seeded here; each needs a decision first
-- (not yet onboarded, or a threshold input to settle).
INSERT INTO tax_rules (id, jurisdiction, tax_type, rate_structure)
SELECT gen_random_uuid(), v.jurisdiction, v.tax_type, v.rate_structure
FROM (VALUES
    ('UK', 'CORPORATE_INCOME_TAX', 'TIERED:50000|0.19,-|0.25;RELIEF:50000|250000|0.015'),
    ('IE', 'CORPORATE_INCOME_TAX', 'CATEGORY:TRADING=0.125,PASSIVE=0.25'),
    ('SL', 'CORPORATE_INCOME_TAX', 'FLAT:0.30')
) AS v(jurisdiction, tax_type, rate_structure)
ON CONFLICT (jurisdiction, tax_type) DO NOTHING;
