package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.tax.VatCategory
import com.theprodeogroup.fish.domain.tax.VatFilingPeriod
import com.theprodeogroup.fish.domain.tax.VatReturn
import com.theprodeogroup.fish.domain.tax.VatReturnDirection
import com.theprodeogroup.fish.domain.tax.VatReturnId
import com.theprodeogroup.fish.domain.tax.VatReturnRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed `VatReturnRepository` (`V25__vat_returns.sql`). Uses
 * `VatReturn.reconstitute()`, not `of()` - same "a saved computation
 * must come back exactly as it was, not get silently recomputed"
 * reasoning as `ExposedTaxComputationRepository`.
 *
 * **`categoryBreakdown` always comes back empty on reload** - not
 * persisted at all (`V25__vat_returns.sql`'s own comment: always
 * re-derivable from already-posted, tagged `journal_lines`, so a second
 * copy would just be a cache that could drift). A caller needing the
 * breakdown for an already-computed `VatReturn` should call
 * `VatReturn.of()` again against the same window, not read it off a
 * reloaded row.
 */
class ExposedVatReturnRepository : VatReturnRepository {

    override fun save(vatReturn: VatReturn): Unit = transaction {
        val exists = VatReturnsTable.selectAll().where { VatReturnsTable.id eq vatReturn.id.value }.count() > 0
        if (exists) {
            VatReturnsTable.update({ VatReturnsTable.id eq vatReturn.id.value }) { statement ->
                populate(statement, vatReturn)
            }
        } else {
            VatReturnsTable.insert { statement ->
                statement[id] = vatReturn.id.value
                populate(statement, vatReturn)
            }
        }
        Unit
    }

    override fun findById(id: VatReturnId): VatReturn? = transaction {
        VatReturnsTable.selectAll().where { VatReturnsTable.id eq id.value }
            .map { it.toVatReturn() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<VatReturn> = transaction {
        VatReturnsTable.selectAll().where { VatReturnsTable.companyId eq companyId.value }
            .map { it.toVatReturn() }
    }

    private fun populate(statement: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, vatReturn: VatReturn) {
        statement[VatReturnsTable.companyId] = vatReturn.companyId.value
        statement[VatReturnsTable.filingPeriodStartDate] = vatReturn.filingPeriod.startDate
        statement[VatReturnsTable.filingPeriodEndDate] = vatReturn.filingPeriod.endDate
        statement[VatReturnsTable.vatControlAccountId] = vatReturn.vatControlAccountId.value
        statement[VatReturnsTable.outputVatAmount] = vatReturn.outputVat.amount
        statement[VatReturnsTable.inputVatAmount] = vatReturn.inputVat.amount
        statement[VatReturnsTable.netVatDueAmount] = vatReturn.netVatDue.amount
        statement[VatReturnsTable.direction] = vatReturn.direction.name
        statement[VatReturnsTable.currency] = vatReturn.outputVat.currency.currencyCode
        statement[VatReturnsTable.computedAt] = vatReturn.computedAt
    }

    private fun ResultRow.toVatReturn(): VatReturn {
        val currency = Currency.getInstance(this[VatReturnsTable.currency])
        return VatReturn.reconstitute(
            id = VatReturnId(this[VatReturnsTable.id]),
            companyId = CompanyId(this[VatReturnsTable.companyId]),
            filingPeriod = VatFilingPeriod(this[VatReturnsTable.filingPeriodStartDate], this[VatReturnsTable.filingPeriodEndDate]),
            vatControlAccountId = AccountId(this[VatReturnsTable.vatControlAccountId]),
            outputVat = Money(this[VatReturnsTable.outputVatAmount], currency),
            inputVat = Money(this[VatReturnsTable.inputVatAmount], currency),
            netVatDue = Money(this[VatReturnsTable.netVatDueAmount], currency),
            direction = VatReturnDirection.valueOf(this[VatReturnsTable.direction]),
            categoryBreakdown = emptyMap<VatCategory, Money>(),
            computedAt = this[VatReturnsTable.computedAt]
        )
    }
}
