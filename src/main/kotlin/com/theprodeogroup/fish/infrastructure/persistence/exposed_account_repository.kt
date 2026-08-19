package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.ledger.Account
import com.theprodeogroup.fish.domain.ledger.AccountClassification
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AccountRepository
import com.theprodeogroup.fish.domain.ledger.AccountType
import com.theprodeogroup.fish.domain.ledger.ExpenseClassification
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

/**
 * Exposed-backed `AccountRepository` (docs/DDD_Design.md Section 10.1).
 * [save] checks existence first rather than using Exposed's `upsert` DSL
 * directly - simpler to reason about correctly across Exposed versions
 * than chasing exact upsert-API signatures, and this isn't a hot path
 * that needs upsert's single-round-trip advantage yet.
 */
class ExposedAccountRepository : AccountRepository {

    override fun save(account: Account): Unit = transaction {
        val exists = AccountsTable.selectAll().where { AccountsTable.id eq account.id.value }.count() > 0
        if (exists) {
            AccountsTable.update({ AccountsTable.id eq account.id.value }) { statement ->
                populate(statement, account)
            }
        } else {
            AccountsTable.insert { statement ->
                statement[id] = account.id.value
                populate(statement, account)
            }
        }
        Unit
    }

    override fun findById(id: AccountId): Account? = transaction {
        AccountsTable.selectAll().where { AccountsTable.id eq id.value }
            .map { it.toAccount() }
            .singleOrNull()
    }

    override fun findAllByCompany(companyId: CompanyId): List<Account> = transaction {
        AccountsTable.selectAll().where { AccountsTable.companyId eq companyId.value }
            .map { it.toAccount() }
    }

    private fun populate(statement: org.jetbrains.exposed.sql.statements.UpdateBuilder<*>, account: Account) {
        statement[AccountsTable.companyId] = account.companyId.value
        statement[AccountsTable.type] = account.type.name
        statement[AccountsTable.classification] = account.classification?.name
        statement[AccountsTable.expenseClassification] = account.expenseClassification?.name
        statement[AccountsTable.code] = account.code
        statement[AccountsTable.name] = account.name
        statement[AccountsTable.parentId] = account.parentId?.value
        statement[AccountsTable.active] = account.active
        statement[AccountsTable.hasPostedActivity] = account.hasPostedActivity
    }

    private fun ResultRow.toAccount(): Account = Account.reconstitute(
        id = AccountId(this[AccountsTable.id]),
        companyId = CompanyId(this[AccountsTable.companyId]),
        type = AccountType.valueOf(this[AccountsTable.type]),
        classification = this[AccountsTable.classification]?.let { AccountClassification.valueOf(it) },
        code = this[AccountsTable.code],
        name = this[AccountsTable.name],
        expenseClassification = this[AccountsTable.expenseClassification]?.let { ExpenseClassification.valueOf(it) },
        parentId = this[AccountsTable.parentId]?.let { AccountId(it) },
        active = this[AccountsTable.active],
        hasPostedActivity = this[AccountsTable.hasPostedActivity]
    )
}
