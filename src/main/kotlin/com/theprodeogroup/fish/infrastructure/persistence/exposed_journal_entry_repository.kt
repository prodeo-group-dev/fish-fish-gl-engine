package com.theprodeogroup.fish.infrastructure.persistence

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.PostingStatus
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalEntryRepository
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.statements.UpdateBuilder
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.Currency

/**
 * Exposed-backed `JournalEntryRepository` (docs/DDD_Design.md Section
 * 10.1). [save] deletes and reinserts all of an entry's `JournalLine`s
 * on every call rather than diffing - lines are immutable once created
 * in practice (only `status` mutates on the parent after a `JournalEntry`
 * exists), so there's nothing to diff against; simple beats clever here.
 *
 * `findAllByCompany` has no direct `journal_entries.company_id` column
 * to filter on (`Company` isn't persisted yet, same reason `accounts`/
 * `periods` have an unconstrained `company_id`) - it goes via `periods`
 * instead (a Company's Periods, then that Period's entries), an
 * implementation detail the interface itself doesn't promise.
 */
class ExposedJournalEntryRepository : JournalEntryRepository {

    override fun save(entry: JournalEntry): Unit = transaction {
        val exists = JournalEntriesTable.selectAll().where { JournalEntriesTable.id eq entry.id.value }.count() > 0
        if (exists) {
            JournalEntriesTable.update({ JournalEntriesTable.id eq entry.id.value }) { statement ->
                populate(statement, entry)
            }
        } else {
            JournalEntriesTable.insert { statement ->
                statement[id] = entry.id.value
                populate(statement, entry)
            }
        }

        JournalLinesTable.deleteWhere { JournalLinesTable.journalEntryId eq entry.id.value }
        entry.lines.forEachIndexed { index, line ->
            JournalLinesTable.insert { statement ->
                statement[journalEntryId] = entry.id.value
                statement[lineIndex] = index
                statement[accountId] = line.accountId.value
                statement[amount] = line.amount.amount
                statement[currency] = line.amount.currency.currencyCode
                statement[side] = line.side.name
                statement[dimensions] = encodeDimensions(line.dimensions)
            }
        }
    }

    override fun findById(id: JournalEntryId): JournalEntry? = transaction {
        JournalEntriesTable.selectAll().where { JournalEntriesTable.id eq id.value }
            .singleOrNull()
            ?.toJournalEntry(loadLines(id))
    }

    override fun findAllByCompany(companyId: CompanyId): List<JournalEntry> = transaction {
        val periodIds = PeriodsTable.selectAll().where { PeriodsTable.companyId eq companyId.value }
            .map { it[PeriodsTable.id] }
        if (periodIds.isEmpty()) {
            emptyList()
        } else {
            JournalEntriesTable.selectAll().where { JournalEntriesTable.periodId inList periodIds }
                .map { row -> row.toJournalEntry(loadLines(JournalEntryId(row[JournalEntriesTable.id]))) }
        }
    }

    override fun findAllByPeriod(periodId: PeriodId): List<JournalEntry> = transaction {
        JournalEntriesTable.selectAll().where { JournalEntriesTable.periodId eq periodId.value }
            .map { row -> row.toJournalEntry(loadLines(JournalEntryId(row[JournalEntriesTable.id]))) }
    }

    private fun loadLines(entryId: JournalEntryId): List<JournalLine> =
        JournalLinesTable.selectAll().where { JournalLinesTable.journalEntryId eq entryId.value }
            .orderBy(JournalLinesTable.lineIndex)
            .map { row ->
                JournalLine(
                    accountId = AccountId(row[JournalLinesTable.accountId]),
                    amount = Money(row[JournalLinesTable.amount], Currency.getInstance(row[JournalLinesTable.currency])),
                    side = TransactionSide.valueOf(row[JournalLinesTable.side]),
                    dimensions = decodeDimensions(row[JournalLinesTable.dimensions])
                )
            }

    private fun populate(statement: UpdateBuilder<*>, entry: JournalEntry) {
        statement[JournalEntriesTable.periodId] = entry.periodId.value
        statement[JournalEntriesTable.entryDate] = entry.date
        statement[JournalEntriesTable.entrySource] = entry.source.name
        statement[JournalEntriesTable.description] = entry.description
        statement[JournalEntriesTable.status] = entry.status.name
        statement[JournalEntriesTable.reversalOfEntryId] = entry.reversalOfEntryId?.value
    }

    private fun ResultRow.toJournalEntry(lines: List<JournalLine>): JournalEntry = JournalEntry.reconstitute(
        id = JournalEntryId(this[JournalEntriesTable.id]),
        periodId = PeriodId(this[JournalEntriesTable.periodId]),
        date = this[JournalEntriesTable.entryDate],
        lines = lines,
        source = JournalSource.valueOf(this[JournalEntriesTable.entrySource]),
        description = this[JournalEntriesTable.description],
        status = PostingStatus.valueOf(this[JournalEntriesTable.status]),
        reversalOfEntryId = this[JournalEntriesTable.reversalOfEntryId]?.let { JournalEntryId(it) }
    )
}
