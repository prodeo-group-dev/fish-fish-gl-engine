package com.theprodeogroup.fish.domain.common

/**
 * A jurisdiction FiSH can onboard a Company into, identified by a
 * two-letter code (`UK`, `IE`, `NG`, `SL`, `LR`, `GN`, `CI`, ...).
 *
 * **Reference data, not a compiled constant (2026-10-05, direct
 * instruction: "What if someone wants to use the software from a new
 * jurisdiction/country, e.g. ZA?").** The approved set lives in the
 * `jurisdictions` table ([JurisdictionRepository]); approving a new one
 * is a data operation, not a code change or deploy. This type only
 * guarantees a well-formed code - whether a code is *approved* is the
 * registry's question, asked at the boundary that accepts one
 * (`POST /tenants/{tenantId}/companies`).
 *
 * Replaces the closed `enum class Jurisdiction` of 2026-09-19 ("make
 * jurisdiction a closed choice... this is an important governance"),
 * which was itself a fix for [com.theprodeogroup.fish.domain.tenancy.Company.jurisdiction]
 * and [com.theprodeogroup.fish.domain.tax.TaxRule.jurisdiction] being
 * independent, unvalidated `String`s that had already drifted ("SL"/"UK"/
 * "Sierra Leone"/"GB" across test fixtures) - the governance intent
 * survives (only approved jurisdictions are accepted), the compile-time
 * list does not. The seven original codes stay as constants so existing
 * code and tests read the same.
 *
 * **The platform's single source of jurisdictions (2026-10-05, "Only one
 * source should provide this information")** - `GET /api/jurisdictions`
 * serves the registry so the frontend and sibling services read it
 * instead of keeping their own copy.
 */
@JvmInline
value class Jurisdiction(val code: String) {
    init {
        require(CODE_FORMAT.matches(code)) { "A jurisdiction code is two uppercase letters, got '$code'" }
    }

    override fun toString(): String = code

    companion object {
        private val CODE_FORMAT = Regex("[A-Z]{2}")

        /** The whole United Kingdom, Northern Ireland included (2026-10-05: "We no longer use GB. We use UK. UK includes Northern Ireland") - deliberately no separate `GB` or `NI` code. */
        val UK = Jurisdiction("UK")
        val IE = Jurisdiction("IE")
        val NG = Jurisdiction("NG")
        val SL = Jurisdiction("SL")
        val LR = Jurisdiction("LR")
        val GN = Jurisdiction("GN")
        val CI = Jurisdiction("CI")
    }
}
