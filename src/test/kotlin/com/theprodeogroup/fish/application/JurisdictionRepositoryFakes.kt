package com.theprodeogroup.fish.application

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionEntry
import com.theprodeogroup.fish.domain.common.JurisdictionRepository

/**
 * In-memory [JurisdictionRepository], pre-seeded with the same seven
 * enabled jurisdictions `V29__jurisdictions.sql` seeds, so a test starts
 * from the production baseline and adds or disables from there.
 */
class FakeJurisdictionRepository : JurisdictionRepository {
    private val store = linkedMapOf<Jurisdiction, JurisdictionEntry>()

    init {
        listOf(
            JurisdictionEntry(Jurisdiction.UK, "United Kingdom (including Northern Ireland)", true),
            JurisdictionEntry(Jurisdiction.IE, "Ireland", true),
            JurisdictionEntry(Jurisdiction.NG, "Nigeria", true),
            JurisdictionEntry(Jurisdiction.SL, "Sierra Leone", true),
            JurisdictionEntry(Jurisdiction.LR, "Liberia", true),
            JurisdictionEntry(Jurisdiction.GN, "Guinea", true),
            JurisdictionEntry(Jurisdiction.CI, "C\u00f4te d'Ivoire", true)
        ).forEach { save(it) }
    }

    override fun save(entry: JurisdictionEntry) {
        store[entry.code] = entry
    }

    override fun findAllEnabled(): List<JurisdictionEntry> = store.values.filter { it.enabled }.sortedBy { it.code.code }

    override fun findEnabledByCode(code: Jurisdiction): JurisdictionEntry? = store[code]?.takeIf { it.enabled }
}
