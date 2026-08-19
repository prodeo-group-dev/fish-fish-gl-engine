package com.theprodeogroup.fish.domain.tax

import java.math.BigDecimal

/**
 * Jurisdiction-specific tax configuration (docs/DDD_Design.md Section
 * 3.4; spec Section 7.12: "stored as data, not hard-coded, since rates
 * and rules change by country and over time"). [rate] is a fraction
 * (`0.30` for 30%), matching `Borrowing.annualInterestRate`'s existing
 * convention.
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
 * A plain `data class`, not a private-constructor aggregate - no
 * identity, no lifecycle, no repository. Matches `JournalLine`'s
 * precedent for a value object embedded by whatever uses it (here,
 * `ComputeTaxUseCase.Request`), not a first-class persisted entity in
 * this build.
 */
data class TaxRule(
    val jurisdiction: String,
    val taxType: TaxType,
    val rate: BigDecimal
) {
    init {
        require(jurisdiction.isNotBlank()) { "TaxRule jurisdiction must not be blank" }
        require(rate.signum() >= 0) { "TaxRule rate cannot be negative" }
    }
}
