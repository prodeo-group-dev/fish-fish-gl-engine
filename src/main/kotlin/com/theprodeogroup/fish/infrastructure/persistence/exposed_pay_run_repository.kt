package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.payroll.PayRun
import com.theprodeogroup.fish.domain.payroll.PayRunId
import com.theprodeogroup.fish.domain.payroll.PayRunRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed `PayRunRepository` (docs/DDD_Design.md Section 10.4).
 * Reconstitution reuses `PayRun.create()` directly rather than adding an
 * `internal reconstitute()` - `PayRun` has no mutable state after
 * construction (`post()` produces a `JournalEntry` but changes nothing on
 * `PayRun` itself), same reasoning as `ExposedUserRepository` reusing
 * `User.create()`.
 */
class ExposedPayRunRepository : PayRunRepository {

    override fun save(payRun: PayRun): Unit = transaction {
        val exists = PayRunsTable.selectAll().where { PayRunsTable.id eq payRun.id.value }.count() > 0
        if (exists) {
            PayRunsTable.update({ PayRunsTable.id eq payRun.id.value }) { statement ->
                statement[companyId] = payRun.companyId.value
                statement[payDate] = payRun.date
                statement[totalWagesAmount] = payRun.totalWages.amount
                statement[totalSalariesAmount] = payRun.totalSalaries.amount
                statement[currency] = payRun.totalWages.currency.currencyCode
            }
        } else {
            PayRunsTable.insert { statement ->
                statement[id] = payRun.id.value
                statement[companyId] = payRun.companyId.value
                statement[payDate] = payRun.date
                statement[totalWagesAmount] = payRun.totalWages.amount
                statement[totalSalariesAmount] = payRun.totalSalaries.amount
                statement[currency] = payRun.totalWages.currency.currencyCode
            }
        }
        Unit
    }

    override fun findById(id: PayRunId): PayRun? = transaction {
        PayRunsTable.selectAll().where { PayRunsTable.id eq id.value }
            .map { it.toPayRun() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<PayRun> = transaction {
        PayRunsTable.selectAll().where { PayRunsTable.companyId eq companyId.value }
            .map { it.toPayRun() }
    }

    private fun ResultRow.toPayRun(): PayRun {
        val currencyValue = Currency.getInstance(this[PayRunsTable.currency])
        return PayRun.create(
            companyId = CompanyId(this[PayRunsTable.companyId]),
            date = this[PayRunsTable.payDate],
            totalWages = Money(this[PayRunsTable.totalWagesAmount], currencyValue),
            totalSalaries = Money(this[PayRunsTable.totalSalariesAmount], currencyValue),
            id = PayRunId(this[PayRunsTable.id])
        )
    }
}
