package com.theprodeogroup.fish.domain.tax

import java.math.BigDecimal

/**
 * Jurisdiction-specific tax configuration (docs/DDD_Design.md Section
 * 3.4; spec Section 7.12: "stored as data, not hard-coded, since rates
 * and rules change by country and over time"). [rateStructure] replaces
 * a single flat `rate: BigDecimal` (2026-08-22) - see [RateStructure]'s
 * own KDoc for why a flat rate alone can't represent what real
 * jurisdictions actually do for Corporate Income Tax.
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
    val rateStructure: RateStructure
) {
    companion object {
        fun create(
            jurisdiction: String,
            taxType: TaxType,
            rateStructure: RateStructure,
            id: TaxRuleId = TaxRuleId.generate()
        ): TaxRule {
            require(jurisdiction.isNotBlank()) { "TaxRule jurisdiction must not be blank" }
            return TaxRule(id, jurisdiction, taxType, rateStructure)
        }

        /**
         * Convenience for the common flat-rate case - wraps [rate] in
         * [RateStructure.Flat]. Matches this class's original
         * single-rate API exactly, so every flat-rate caller (Sierra
         * Leone, and any other jurisdiction with no tiering/category/
         * exemption shape) keeps working unchanged; validation of
         * [rate] itself now lives in [RateStructure.Flat]'s own `init`.
         */
        fun create(
            jurisdiction: String,
            taxType: TaxType,
            rate: BigDecimal,
            id: TaxRuleId = TaxRuleId.generate()
        ): TaxRule = create(jurisdiction, taxType, RateStructure.Flat(rate), id)
    }
}
