package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionEntry
import com.theprodeogroup.fish.domain.common.JurisdictionRepository
import java.util.Currency

/**
 * In-memory [JurisdictionRepository], pre-seeded with the same seven
 * enabled jurisdictions `V29__jurisdictions.sql` seeds, so a test starts
 * from the production baseline and adds or disables from there.
 */
class FakeJurisdictionRepository : JurisdictionRepository {
    private val store = linkedMapOf<Jurisdiction, JurisdictionEntry>()

    init {
        listOf(
            JurisdictionEntry(Jurisdiction.UK, "United Kingdom (including Northern Ireland)", true, Currency.getInstance("GBP")),
            JurisdictionEntry(Jurisdiction.IE, "Ireland", true, Currency.getInstance("EUR")),
            JurisdictionEntry(Jurisdiction.NG, "Nigeria", true, Currency.getInstance("NGN")),
            JurisdictionEntry(Jurisdiction.SL, "Sierra Leone", true, Currency.getInstance("SLE")),
            JurisdictionEntry(Jurisdiction.LR, "Liberia", true, Currency.getInstance("SLE")),
            JurisdictionEntry(Jurisdiction.GN, "Guinea", true, Currency.getInstance("SLE")),
            JurisdictionEntry(Jurisdiction.CI, "C\u00f4te d'Ivoire", true, Currency.getInstance("SLE"))
        ).forEach { save(it) }
    }

    override fun save(entry: JurisdictionEntry) {
        store[entry.code] = entry
    }

    override fun findAllEnabled(): List<JurisdictionEntry> = store.values.filter { it.enabled }.sortedBy { it.code.code }

    override fun findEnabledByCode(code: Jurisdiction): JurisdictionEntry? = store[code]?.takeIf { it.enabled }
}
