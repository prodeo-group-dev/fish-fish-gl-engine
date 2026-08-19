package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.purchasing.Creditor
import com.theprodeogroup.fish.domain.purchasing.CreditorId
import com.theprodeogroup.fish.domain.purchasing.CreditorRepository
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/** Exposed-backed `CreditorRepository` (docs/DDD_Design.md Section 10.4). Same existence-check-then-insert-or-update shape as every other repository in this codebase. */
class ExposedCreditorRepository : CreditorRepository {

    override fun save(creditor: Creditor): Unit = transaction {
        val exists = CreditorsTable.selectAll().where { CreditorsTable.id eq creditor.id.value }.count() > 0
        if (exists) {
            CreditorsTable.update({ CreditorsTable.id eq creditor.id.value }) { statement ->
                populate(statement, creditor)
            }
        } else {
            CreditorsTable.insert { statement ->
                statement[id] = creditor.id.value
                populate(statement, creditor)
            }
        }
        Unit
    }

    override fun findById(id: CreditorId): Creditor? = transaction {
        CreditorsTable.selectAll().where { CreditorsTable.id eq id.value }
            .map { it.toCreditor() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<Creditor> = transaction {
        CreditorsTable.selectAll().where { CreditorsTable.companyId eq companyId.value }
            .map { it.toCreditor() }
    }

    private fun populate(statement: UpdateBuilder<*>, creditor: Creditor) {
        statement[CreditorsTable.companyId] = creditor.companyId.value
        statement[CreditorsTable.name] = creditor.name
        statement[CreditorsTable.currency] = creditor.currency.currencyCode
        statement[CreditorsTable.balanceAmount] = creditor.balance.amount
    }

    private fun ResultRow.toCreditor(): Creditor {
        val currency = Currency.getInstance(this[CreditorsTable.currency])
        return Creditor.reconstitute(
            id = CreditorId(this[CreditorsTable.id]),
            companyId = CompanyId(this[CreditorsTable.companyId]),
            name = this[CreditorsTable.name],
            currency = currency,
            balance = Money(this[CreditorsTable.balanceAmount], currency)
        )
    }
}
