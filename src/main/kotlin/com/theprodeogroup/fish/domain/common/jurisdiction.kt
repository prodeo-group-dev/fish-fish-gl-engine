package com.theprodeogroup.fish.domain.common

/**
 * A jurisdiction FiSH has real, sourced tax/currency reference data for -
 * the seven `docs/<CODE>/` folders (UK, IE, NG, SL, LR, GN, CI) that
 * `RateStructure`'s own KDoc confirms cover every Corporate Income Tax
 * shape found across this project's reference research.
 *
 * A closed choice, not free text (2026-09-19, direct instruction: "make
 * jurisdiction a closed choice against those seven... this is an
 * important governance"). Before this, [com.theprodeogroup.fish.domain.tenancy.Company.jurisdiction]
 * and [com.theprodeogroup.fish.domain.tax.TaxRule.jurisdiction] were both
 * independent, unvalidated `String`s - nothing guaranteed they'd ever
 * actually match, even though `TaxRoutes.kt` looks up a `TaxRule` by
 * `company.jurisdiction` verbatim to compute Corporate Income Tax. That
 * wasn't a hypothetical risk: test fixtures had already drifted before
 * this enum existed ("SL"/"UK"/"Sierra Leone"/"GB" across different
 * `Company` tests; "Sierra Leone"/"Liberia"/"Nigeria" across different
 * `TaxRule` tests, sometimes with a random suffix for uniqueness). A
 * real, live fragility this closes by construction, not just a
 * validation nicety.
 */
enum class Jurisdiction {
    UK, IE, NG, SL, LR, GN, CI
}
