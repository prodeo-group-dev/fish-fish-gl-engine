package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/**
 * Exposed table definitions for [com.theprodeogroup.fish.domain.ledger.BankReconciliation]
 * (`V28__bank_reconciliation_tables.sql`, docs/GL_Working_Capital_And_Bank_Reconciliation_Software_Requirements_Specification.md
 * Section 4).
 */
object BankReconciliationsTable : Table("bank_reconciliations") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val accountId = uuid("account_id")
    val statementDate = date("statement_date")
    val statementEndingBalanceAmount = decimal("statement_ending_balance_amount", 19, 4)
    val currency = varchar("currency", 3)
    val status = varchar("status", 20)

    override val primaryKey = PrimaryKey(id)
}

/** Embedded child of a [BankReconciliationsTable] row - no independent lifecycle, inserted once at Start time. */
object BankStatementLinesTable : Table("bank_statement_lines") {
    val id = uuid("id")
    val reconciliationId = uuid("reconciliation_id")
    val lineDate = date("line_date")
    val amount = decimal("amount", 19, 4)
    val direction = varchar("direction", 10)
    val description = varchar("description", 500)

    override val primaryKey = PrimaryKey(id)
}

/** Plain insert/delete pairs - not append-only, per FR-BANKREC-04's decided shape. */
object BankReconciliationMatchesTable : Table("bank_reconciliation_matches") {
    val statementLineId = uuid("statement_line_id")
    val journalEntryId = uuid("journal_entry_id")

    override val primaryKey = PrimaryKey(statementLineId)
}
