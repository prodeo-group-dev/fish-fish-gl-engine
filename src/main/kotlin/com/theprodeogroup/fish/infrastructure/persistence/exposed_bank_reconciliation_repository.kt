package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.BankReconciliationStatus
import com.theprodeogroup.fish.domain.ledger.BankStatementLine
import com.theprodeogroup.fish.domain.ledger.BankStatementLineId
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed [BankReconciliationRepository]. `save()` upserts the
 * reconciliation's own row and its statement lines (both immutable
 * after creation - inserted only if not already present), then
 * reconciles [BankReconciliationMatchesTable] against the aggregate's
 * current in-memory [BankReconciliation.currentMatches] via a full
 * delete-and-reinsert - same pattern already established for join
 * tables in this codebase (`exposed_tenant_repository.kt`'s own
 * membership/module-grant reconciliation), simpler than diffing given
 * how few matches a single reconciliation typically has.
 */
class ExposedBankReconciliationRepository : BankReconciliationRepository {

    override fun save(reconciliation: BankReconciliation, companyId: CompanyId): Unit = transaction {
        val exists = BankReconciliationsTable.selectAll()
            .where { BankReconciliationsTable.id eq reconciliation.id.value }
            .count() > 0
        if (!exists) {
            BankReconciliationsTable.insert { statement ->
                statement[id] = reconciliation.id.value
                statement[BankReconciliationsTable.companyId] = companyId.value
                statement[accountId] = reconciliation.accountId.value
                statement[statementDate] = reconciliation.statementDate
                statement[statementEndingBalanceAmount] = reconciliation.statementEndingBalance.amount
                statement[currency] = reconciliation.currency.currencyCode
                statement[BankReconciliationsTable.status] = reconciliation.status.name
            }
            reconciliation.statementLines.forEach { line ->
                BankStatementLinesTable.insert { statement ->
                    statement[id] = line.id.value
                    statement[reconciliationId] = reconciliation.id.value
                    statement[lineDate] = line.date
                    statement[amount] = line.amount.amount
                    statement[direction] = line.direction.name
                    statement[description] = line.description
                }
            }
        } else {
            BankReconciliationsTable.update({ BankReconciliationsTable.id eq reconciliation.id.value }) { statement ->
                statement[BankReconciliationsTable.status] = reconciliation.status.name
            }
        }

        val lineIds = reconciliation.statementLines.map { it.id.value }
        if (lineIds.isNotEmpty()) {
            BankReconciliationMatchesTable.deleteWhere { statementLineId inList lineIds }
        }
        reconciliation.currentMatches.forEach { (statementLineId, journalEntryId) ->
            BankReconciliationMatchesTable.insert { statement ->
                statement[BankReconciliationMatchesTable.statementLineId] = statementLineId.value
                statement[BankReconciliationMatchesTable.journalEntryId] = journalEntryId.value
            }
        }
        Unit
    }

    override fun findById(id: BankReconciliationId, companyId: CompanyId, postedEntries: List<JournalEntry>): BankReconciliation? = transaction {
        val row = BankReconciliationsTable.selectAll()
            .where { (BankReconciliationsTable.id eq id.value) and (BankReconciliationsTable.companyId eq companyId.value) }
            .singleOrNull() ?: return@transaction null

        row.toReconciliation(postedEntries)
    }

    override fun findAllByCompany(companyId: CompanyId, postedEntries: List<JournalEntry>, accountId: AccountId?): List<BankReconciliation> = transaction {
        var condition = BankReconciliationsTable.companyId eq companyId.value
        accountId?.let { condition = condition and (BankReconciliationsTable.accountId eq it.value) }

        BankReconciliationsTable.selectAll().where { condition }.map { it.toReconciliation(postedEntries) }
    }

    /** Shared row-to-aggregate loading for [findById]/[findAllByCompany] - each reconciliation's statement lines/matches are still a per-row sub-query, fine at the scale a Company's own reconciliations run at (monthly per Account, not thousands). */
    private fun ResultRow.toReconciliation(postedEntries: List<JournalEntry>): BankReconciliation {
        val reconciliationId = BankReconciliationId(this[BankReconciliationsTable.id])
        val currency = Currency.getInstance(this[BankReconciliationsTable.currency])
        val statementLines = BankStatementLinesTable.selectAll()
            .where { BankStatementLinesTable.reconciliationId eq reconciliationId.value }
            .map { lineRow ->
                BankStatementLine(
                    id = BankStatementLineId(lineRow[BankStatementLinesTable.id]),
                    date = lineRow[BankStatementLinesTable.lineDate],
                    amount = Money(lineRow[BankStatementLinesTable.amount], currency),
                    direction = CashDirection.valueOf(lineRow[BankStatementLinesTable.direction]),
                    description = lineRow[BankStatementLinesTable.description]
                )
            }

        val lineIds = statementLines.map { it.id.value }
        val matches = if (lineIds.isEmpty()) emptySet() else {
            BankReconciliationMatchesTable.selectAll()
                .where { BankReconciliationMatchesTable.statementLineId inList lineIds }
                .map { matchRow ->
                    BankStatementLineId(matchRow[BankReconciliationMatchesTable.statementLineId]) to
                        JournalEntryId(matchRow[BankReconciliationMatchesTable.journalEntryId])
                }.toSet()
        }

        return BankReconciliation.reconstitute(
            id = reconciliationId,
            accountId = AccountId(this[BankReconciliationsTable.accountId]),
            statementDate = this[BankReconciliationsTable.statementDate],
            statementEndingBalance = Money(this[BankReconciliationsTable.statementEndingBalanceAmount], currency),
            statementLines = statementLines,
            postedEntries = postedEntries,
            currency = currency,
            matches = matches,
            status = BankReconciliationStatus.valueOf(this[BankReconciliationsTable.status])
        )
    }
}
