package com.theprodeogroup.fish.domain.common

/**
 * One row of the jurisdiction registry (see [Jurisdiction]'s own KDoc).
 * [enabled] is the governance gate: a jurisdiction is offered for
 * onboarding only once Prodeo has switched it on - a human approval,
 * deliberately not derived from "tax data exists", since a jurisdiction
 * needs more than a TaxRule before it is ready (VAT, currency, legal
 * presence).
 */
data class JurisdictionEntry(val code: Jurisdiction, val name: String, val enabled: Boolean) {
    init {
        require(name.isNotBlank()) { "A jurisdiction's name must not be blank" }
    }
}

/**
 * The registry of approved jurisdictions. Deliberately small: reads for
 * the onboarding dropdown and the add-company check, and [save] for
 * seeding and the future operator write path. Adding a jurisdiction is a
 * data operation - there is no code path that needs changing.
 */
interface JurisdictionRepository {
    fun save(entry: JurisdictionEntry)

    /** Enabled entries only, ordered by code - what onboarding may offer. */
    fun findAllEnabled(): List<JurisdictionEntry>

    /** The entry for [code] only if it exists AND is enabled - the single check `POST .../companies` makes. */
    fun findEnabledByCode(code: Jurisdiction): JurisdictionEntry?
}
