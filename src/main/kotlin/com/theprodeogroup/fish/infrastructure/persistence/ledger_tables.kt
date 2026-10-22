package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.date
import org.jetbrains.exposed.sql.javatime.timestamp
import java.math.BigDecimal

/**
 * Exposed table definitions for the core Ledger schema
 * (`V2__core_ledger_tables.sql`, docs/DDD_Design.md Section 10.1) -
 * matches the migration's DDL exactly. Kept in `infrastructure`, not
 * `domain` - the domain aggregates (`Account`/`Period`/`JournalEntry`)
 * know nothing about how they're stored.
 */
object AccountsTable : Table("accounts") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val type = varchar("type", 20)
    val classification = varchar("classification", 20).nullable()
    val expenseClassification = varchar("expense_classification", 30).nullable()
    val code = varchar("code", 50)
    val name = varchar("name", 255)
    val parentId = uuid("parent_id").nullable()
    val active = bool("active")
    val hasPostedActivity = bool("has_posted_activity")
    val cashBookKind = varchar("cash_book_kind", 10).nullable()

    override val primaryKey = PrimaryKey(id)
}

object PeriodsTable : Table("periods") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val periodType = varchar("period_type", 20)
    val startDate = date("start_date")
    val endDate = date("end_date")
    val status = varchar("status", 20)

    override val primaryKey = PrimaryKey(id)
}

object JournalEntriesTable : Table("journal_entries") {
    val id = uuid("id")
    val periodId = uuid("period_id")
    val entryDate = date("entry_date")

    // Named entrySource, not source - "source" collides with a member
    // Exposed's own ColumnSet/Table base class already declares. The
    // actual DB column name (below) is still plain "source".
    val entrySource = varchar("source", 20)
    val description = text("description").nullable()
    val status = varchar("status", 20)
    val reversalOfEntryId = uuid("reversal_of_entry_id").nullable()

    /** Assigned by the database on insert (V35 identity column), never written by the application (`autoIncrement` makes Exposed leave it out of inserts so Postgres supplies it). */
    val recordedSeq = long("recorded_seq").autoIncrement()

    /** When the database stored the entry (V35 `DEFAULT now()`), never written by the application: read-only, shown beside the accounting date (GL-FR-LED-9). */
    val recordedAt = timestamp("recorded_at").defaultExpression(CurrentTimestamp)

    // V36: who, why and what document, written once at insert from the request's audit context (GL-T-38, GL-T-31). All nullable.
    val recordedBy = varchar("recorded_by", 120).nullable()
    val actingUser = varchar("acting_user", 64).nullable()
    val reason = varchar("reason", 500).nullable()
    val sourceRefType = varchar("source_ref_type", 40).nullable()
    val sourceRefId = varchar("source_ref_id", 100).nullable()
    val sourceRefNumber = varchar("source_ref_number", 100).nullable()
    val vatBasisRatePercent = varchar("vat_basis_rate_percent", 20).nullable()
    val vatBasisRulebookVersion = integer("vat_basis_rulebook_version").nullable()
    val vatBasisEffectiveFrom = date("vat_basis_effective_from").nullable()

    override val primaryKey = PrimaryKey(id)
}

object JournalLinesTable : Table("journal_lines") {
    val journalEntryId = uuid("journal_entry_id")
    val lineIndex = integer("line_index")
    val accountId = uuid("account_id")
    val amount = decimal("amount", 19, 4)
    val currency = varchar("currency", 3)
    val side = varchar("side", 10)
    val dimensions = text("dimensions")

    override val primaryKey = PrimaryKey(journalEntryId, lineIndex)
}
