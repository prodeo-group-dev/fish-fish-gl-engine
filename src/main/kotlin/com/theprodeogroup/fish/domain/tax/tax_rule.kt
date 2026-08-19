package com.theprodeogroup.fish.domain.tax

import java.math.BigDecimal

/**
 * Jurisdiction-specific tax configuration (docs/DDD_Design.md Section
 * 3.4; spec Section 7.12: "stored as data, not hard-coded, since rates
 * and rules change by country and over time"). [rate] is a fraction
 * (`0.30` for 30%), matching `Borrowing.annualInterestRate`'s existing
 * convention.
 *
 * **Global reference data, not scoped to any Tenant/Company** - built
 * with real identity and a repository (docs/DDD_Design.md Section
 * 10.11), confirmed with the user before building: a jurisdiction's
 * statutory tax rate is the same for every Company operating there,
 * regardless of which Tenant it belongs to - it isn't something each
 * Tenant should configure its own copy of.
 *
 * **No `applicableAccountIds`/period-scoping field**, despite spec
 * 7.12's "which accounts/periods it applies to" - deliberately deferred,
 * not an oversight. [TaxType.CORPORATE_INCOME_TAX] (the only supported
 * type) is levied on a Company's *entire* net profit for a Period, not a
 * subset of accounts - there's no meaningful "which accounts" question
 * to answer for this tax type. That field becomes real once a tax type
 * needing genuine per-account/per-transaction applicability (VAT/GST) is
 * actually built - see [TaxType]'s own KDoc.
 *
 * **No effective-dating/rate-history modeling** - a known, flagged
 * limitation, not silently glossed over. If a jurisdiction's rate
 * changes, there's currently no way to represent "this rate applied
 * until date X, this other rate from date X onward" - only ever "the
 * current TaxRule for this jurisdiction/type." Revisit if/when tax-rate
 * history actually needs representing (e.g. recomputing a prior year's
 * filing under the rate that applied then).
 */
class TaxRule private constructor(
    val id: TaxRuleId,
    val jurisdiction: String,
    val taxType: TaxType,
    val rate: BigDecimal
) {
    companion object {
        fun create(
            jurisdiction: String,
            taxType: TaxType,
            rate: BigDecimal,
            id: TaxRuleId = TaxRuleId.generate()
        ): TaxRule {
            require(jurisdiction.isNotBlank()) { "TaxRule jurisdiction must not be blank" }
            require(rate.signum() >= 0) { "TaxRule rate cannot be negative" }
            return TaxRule(id, jurisdiction, taxType, rate)
        }
    }
}
