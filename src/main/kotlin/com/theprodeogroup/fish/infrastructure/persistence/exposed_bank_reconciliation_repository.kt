package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.BankReconciliation
import com.theprodeogroup.fish.domain.ledger.BankReconciliationId
import com.theprodeogroup.fish.domain.ledger.BankReconciliationRepository
import com.theprodeogroup.fish.domain.ledger.BankStatementLine
import com.theprodeogroup.fish.domain.ledger.BankStatementLineId
import com.theprodeogroup.fish.domain.ledger.CashDirection
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
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

        val currency = Currency.getInstance(row[BankReconciliationsTable.currency])
        val statementLines = BankStatementLinesTable.selectAll()
            .where { BankStatementLinesTable.reconciliationId eq id.value }
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

        BankReconciliation.reconstitute(
            id = id,
            accountId = AccountId(row[BankReconciliationsTable.accountId]),
            statementDate = row[BankReconciliationsTable.statementDate],
            statementEndingBalance = Money(row[BankReconciliationsTable.statementEndingBalanceAmount], currency),
            statementLines = statementLines,
            postedEntries = postedEntries,
            currency = currency,
            matches = matches
        )
    }
}
