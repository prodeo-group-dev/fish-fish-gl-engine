package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tax.TaxComputation
import com.theprodeogroup.fish.domain.tax.TaxComputationId
import com.theprodeogroup.fish.domain.tax.TaxComputationRepository
import com.theprodeogroup.fish.domain.tax.TaxRuleId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed `TaxComputationRepository` (docs/DDD_Design.md Section
 * 10.11). Uses `TaxComputation.reconstitute()`, not `of()` - a saved
 * computation reflects a specific point-in-time calculation that must
 * come back exactly as it was, not get silently recomputed against
 * whatever the Ledger data looks like now.
 */
class ExposedTaxComputationRepository : TaxComputationRepository {

    override fun save(taxComputation: TaxComputation): Unit = transaction {
        val exists = TaxComputationsTable.selectAll().where { TaxComputationsTable.id eq taxComputation.id.value }.count() > 0
        if (exists) {
            TaxComputationsTable.update({ TaxComputationsTable.id eq taxComputation.id.value }) { statement ->
                populate(statement, taxComputation)
            }
        } else {
            TaxComputationsTable.insert { statement ->
                statement[id] = taxComputation.id.value
                populate(statement, taxComputation)
            }
        }
        Unit
    }

    override fun findById(id: TaxComputationId): TaxComputation? = transaction {
        TaxComputationsTable.selectAll().where { TaxComputationsTable.id eq id.value }
            .map { it.toTaxComputation() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<TaxComputation> = transaction {
        TaxComputationsTable.selectAll().where { TaxComputationsTable.companyId eq companyId.value }
            .map { it.toTaxComputation() }
    }

    private fun populate(statement: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, taxComputation: TaxComputation) {
        statement[TaxComputationsTable.companyId] = taxComputation.companyId.value
        statement[TaxComputationsTable.periodId] = taxComputation.periodId.value
        statement[TaxComputationsTable.taxRuleId] = taxComputation.taxRuleId.value
        statement[TaxComputationsTable.taxableProfitAmount] = taxComputation.taxableProfit.amount
        statement[TaxComputationsTable.taxDueAmount] = taxComputation.taxDue.amount
        statement[TaxComputationsTable.currency] = taxComputation.taxableProfit.currency.currencyCode
        statement[TaxComputationsTable.computedAt] = taxComputation.computedAt
    }

    private fun ResultRow.toTaxComputation(): TaxComputation {
        val currency = Currency.getInstance(this[TaxComputationsTable.currency])
        return TaxComputation.reconstitute(
            id = TaxComputationId(this[TaxComputationsTable.id]),
            companyId = CompanyId(this[TaxComputationsTable.companyId]),
            periodId = PeriodId(this[TaxComputationsTable.periodId]),
            taxRuleId = TaxRuleId(this[TaxComputationsTable.taxRuleId]),
            taxableProfit = Money(this[TaxComputationsTable.taxableProfitAmount], currency),
            taxDue = Money(this[TaxComputationsTable.taxDueAmount], currency),
            computedAt = this[TaxComputationsTable.computedAt]
        )
    }
}
