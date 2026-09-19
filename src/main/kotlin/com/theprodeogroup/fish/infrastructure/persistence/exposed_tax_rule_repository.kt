package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.Jurisdiction
import com.theprodeogroup.fish.domain.tax.TaxRule
import com.theprodeogroup.fish.domain.tax.TaxRuleId
import com.theprodeogroup.fish.domain.tax.TaxRuleRepository
import com.theprodeogroup.fish.domain.tax.TaxType
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Exposed-backed `TaxRuleRepository` (docs/DDD_Design.md Section 10.11).
 * Reconstitution reuses `TaxRule.create()` directly rather than adding an
 * `internal reconstitute()` - `TaxRule` has no mutable state at all after
 * construction, same reasoning as `ExposedUserRepository`/
 * `ExposedPayRunRepository` reusing their aggregates' `create()`.
 */
class ExposedTaxRuleRepository : TaxRuleRepository {

    override fun save(taxRule: TaxRule): Unit = transaction {
        val exists = TaxRulesTable.selectAll().where { TaxRulesTable.id eq taxRule.id.value }.count() > 0
        if (exists) {
            TaxRulesTable.update({ TaxRulesTable.id eq taxRule.id.value }) { statement ->
                statement[jurisdiction] = taxRule.jurisdiction.name
                statement[taxType] = taxRule.taxType.name
                statement[rateStructure] = encodeRateStructure(taxRule.rateStructure)
            }
        } else {
            TaxRulesTable.insert { statement ->
                statement[id] = taxRule.id.value
                statement[jurisdiction] = taxRule.jurisdiction.name
                statement[taxType] = taxRule.taxType.name
                statement[rateStructure] = encodeRateStructure(taxRule.rateStructure)
            }
        }
        Unit
    }

    override fun findById(id: TaxRuleId): TaxRule? = transaction {
        TaxRulesTable.selectAll().where { TaxRulesTable.id eq id.value }
            .map { it.toTaxRule() }
            .singleOrNull()
    }

    override fun findByJurisdictionAndTaxType(jurisdiction: Jurisdiction, taxType: TaxType): TaxRule? = transaction {
        TaxRulesTable.selectAll()
            .where { (TaxRulesTable.jurisdiction eq jurisdiction.name) and (TaxRulesTable.taxType eq taxType.name) }
            .map { it.toTaxRule() }
            .singleOrNull()
    }

    private fun ResultRow.toTaxRule(): TaxRule = TaxRule.create(
        jurisdiction = Jurisdiction.valueOf(this[TaxRulesTable.jurisdiction]),
        taxType = TaxType.valueOf(this[TaxRulesTable.taxType]),
        rateStructure = decodeRateStructure(this[TaxRulesTable.rateStructure]),
        id = TaxRuleId(this[TaxRulesTable.id])
    )
}
