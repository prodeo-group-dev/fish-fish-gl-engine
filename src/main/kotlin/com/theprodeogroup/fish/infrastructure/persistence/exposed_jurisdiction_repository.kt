package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.common.JurisdictionEntry
import com.theprodeogroup.fish.domain.common.JurisdictionRepository
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.upsert

/** Exposed-backed [JurisdictionRepository]. */
class ExposedJurisdictionRepository : JurisdictionRepository {

    override fun save(entry: JurisdictionEntry): Unit = transaction {
        JurisdictionsTable.upsert { statement ->
            statement[code] = entry.code.code
            statement[name] = entry.name
            statement[enabled] = entry.enabled
        }
        Unit
    }

    override fun findAllEnabled(): List<JurisdictionEntry> = transaction {
        JurisdictionsTable.selectAll()
            .where { JurisdictionsTable.enabled eq true }
            .orderBy(JurisdictionsTable.code)
            .map { it.toEntry() }
    }

    override fun findEnabledByCode(code: Jurisdiction): JurisdictionEntry? = transaction {
        JurisdictionsTable.selectAll()
            .where { (JurisdictionsTable.code eq code.code) and (JurisdictionsTable.enabled eq true) }
            .map { it.toEntry() }
            .singleOrNull()
    }

    private fun ResultRow.toEntry() = JurisdictionEntry(
        code = Jurisdiction(this[JurisdictionsTable.code]),
        name = this[JurisdictionsTable.name],
        enabled = this[JurisdictionsTable.enabled]
    )
}
