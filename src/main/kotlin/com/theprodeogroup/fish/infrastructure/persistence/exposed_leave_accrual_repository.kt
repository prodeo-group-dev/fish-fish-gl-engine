package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.Provision
import com.theprodeogroup.fish.domain.ledger.ProvisionId
import com.theprodeogroup.fish.domain.payroll.EmployeeId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrual
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualId
import com.theprodeogroup.fish.domain.payroll.LeaveAccrualRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed `LeaveAccrualRepository` (docs/DDD_Design.md Section
 * 10.18). Reconstitution uses `LeaveAccrual.reconstitute()` +
 * `Provision.reconstitute()` (both `internal`, same module) - the
 * embedded `Provision`'s state is read via `LeaveAccrual.provisionSnapshot()`
 * on save, and rebuilt via `Provision.reconstitute()` on load, since
 * `Provision` has no repository/table of its own.
 */
class ExposedLeaveAccrualRepository : LeaveAccrualRepository {

    override fun save(leaveAccrual: LeaveAccrual): Unit = transaction {
        val provision = leaveAccrual.provisionSnapshot()
        val exists = LeaveAccrualsTable.selectAll().where { LeaveAccrualsTable.id eq leaveAccrual.id.value }.count() > 0
        if (exists) {
            LeaveAccrualsTable.update({ LeaveAccrualsTable.id eq leaveAccrual.id.value }) { statement ->
                statement[companyId] = leaveAccrual.companyId.value
                statement[employeeId] = leaveAccrual.employeeId.value
                statement[provisionId] = provision.id.value
                statement[provisionDescription] = provision.description
                statement[balanceAmount] = leaveAccrual.balance.amount
                statement[currency] = leaveAccrual.balance.currency.currencyCode
            }
        } else {
            LeaveAccrualsTable.insert { statement ->
                statement[id] = leaveAccrual.id.value
                statement[companyId] = leaveAccrual.companyId.value
                statement[employeeId] = leaveAccrual.employeeId.value
                statement[provisionId] = provision.id.value
                statement[provisionDescription] = provision.description
                statement[balanceAmount] = leaveAccrual.balance.amount
                statement[currency] = leaveAccrual.balance.currency.currencyCode
            }
        }
        Unit
    }

    override fun findById(id: LeaveAccrualId): LeaveAccrual? = transaction {
        LeaveAccrualsTable.selectAll().where { LeaveAccrualsTable.id eq id.value }
            .map { it.toLeaveAccrual() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<LeaveAccrual> = transaction {
        LeaveAccrualsTable.selectAll().where { LeaveAccrualsTable.companyId eq companyId.value }
            .map { it.toLeaveAccrual() }
    }

    private fun ResultRow.toLeaveAccrual(): LeaveAccrual {
        val currencyValue = Currency.getInstance(this[LeaveAccrualsTable.currency])
        val companyIdValue = CompanyId(this[LeaveAccrualsTable.companyId])
        val provision = Provision.reconstitute(
            id = ProvisionId(this[LeaveAccrualsTable.provisionId]),
            companyId = companyIdValue,
            description = this[LeaveAccrualsTable.provisionDescription],
            currency = currencyValue,
            balance = Money(this[LeaveAccrualsTable.balanceAmount], currencyValue)
        )
        return LeaveAccrual.reconstitute(
            id = LeaveAccrualId(this[LeaveAccrualsTable.id]),
            companyId = companyIdValue,
            employeeId = EmployeeId(this[LeaveAccrualsTable.employeeId]),
            provision = provision
        )
    }
}
