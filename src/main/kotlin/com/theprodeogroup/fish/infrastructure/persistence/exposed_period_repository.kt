package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.PeriodStatus
import com.theprodeogroup.fish.domain.common.PeriodType
import com.theprodeogroup.fish.domain.ledger.Period
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.PeriodRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/** Exposed-backed `PeriodRepository` (docs/DDD_Design.md Section 10.1). Same existence-check-then-insert-or-update shape as `ExposedAccountRepository`. */
class ExposedPeriodRepository : PeriodRepository {

    override fun save(period: Period): Unit = transaction {
        val exists = PeriodsTable.selectAll().where { PeriodsTable.id eq period.id.value }.count() > 0
        if (exists) {
            PeriodsTable.update({ PeriodsTable.id eq period.id.value }) { statement ->
                populate(statement, period)
            }
        } else {
            PeriodsTable.insert { statement ->
                statement[id] = period.id.value
                populate(statement, period)
            }
        }
        Unit
    }

    override fun findById(id: PeriodId): Period? = transaction {
        PeriodsTable.selectAll().where { PeriodsTable.id eq id.value }
            .map { it.toPeriod() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<Period> = transaction {
        PeriodsTable.selectAll().where { PeriodsTable.companyId eq companyId.value }
            .map { it.toPeriod() }
    }

    private fun populate(statement: UpdateBuilder<*>, period: Period) {
        statement[PeriodsTable.companyId] = period.companyId.value
        statement[PeriodsTable.periodType] = period.periodType.name
        statement[PeriodsTable.startDate] = period.startDate
        statement[PeriodsTable.endDate] = period.endDate
        statement[PeriodsTable.status] = period.status.name
    }

    private fun ResultRow.toPeriod(): Period = Period.reconstitute(
        id = PeriodId(this[PeriodsTable.id]),
        companyId = CompanyId(this[PeriodsTable.companyId]),
        periodType = PeriodType.valueOf(this[PeriodsTable.periodType]),
        startDate = this[PeriodsTable.startDate],
        endDate = this[PeriodsTable.endDate],
        status = PeriodStatus.valueOf(this[PeriodsTable.status])
    )
}
