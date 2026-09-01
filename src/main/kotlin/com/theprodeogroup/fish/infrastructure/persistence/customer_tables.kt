package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

// SalesOrdersTable/SalesOrderLinesTable/SalesOrderDeliveredLinesTable
// (backing GL's own legacy SalesOrder aggregate) were removed from this
// file 2026-09-01 ("Retire GL's StockItem from its legacy costing") -
// SOP is the source of truth for sales orders now. CustomersTable and
// SalesInvoiceRecordsTable stay; they still back AR aging/ECL and the
// SOP dashboard's sales listing respectively.

/**
 * Exposed table definition for [com.theprodeogroup.fish.domain.sales.Customer]
 * (`V4__ecosystem_tables.sql`, docs/DDD_Design.md Section 10.4).
 */
object CustomersTable : Table("customers") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val name = varchar("name", 255)
    val currency = varchar("currency", 3)
    val balanceAmount = decimal("balance_amount", 19, 4)
    val allowanceForExpectedCreditLossAmount = decimal("allowance_for_expected_credit_loss_amount", 19, 4)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed table definition for [com.theprodeogroup.fish.domain.sales.SalesInvoiceRecord]
 * (`V14__sales_invoice_records.sql`) - append-only, no update path.
 */
object SalesInvoiceRecordsTable : Table("sales_invoice_records") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val journalEntryId = uuid("journal_entry_id")
    val invoiceNumber = varchar("invoice_number", 32)
    val customerId = uuid("customer_id")
    val customerName = varchar("customer_name", 255)
    val saleType = varchar("sale_type", 10)
    val saleMethod = varchar("sale_method", 10)
    val amount = decimal("amount", 19, 4)
    val currency = varchar("currency", 3)
    val paid = bool("paid")
    val description = varchar("description", 500).nullable()
    val recordedAt = timestamp("recorded_at")

    override val primaryKey = PrimaryKey(id)
}
