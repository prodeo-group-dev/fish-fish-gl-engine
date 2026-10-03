package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

// PurchaseOrdersTable/PurchaseOrderLinesTable (backing GL's own legacy
// PurchaseOrder aggregate) were removed from this file 2026-09-01
// ("Retire GL's StockItem from its legacy costing") - POP is the
// source of truth for purchase orders now. SuppliersTable stays; it
// still backs AccountsPayableAging and Supplier itself.

/**
 * Exposed table definition for [com.theprodeogroup.fish.domain.purchasing.Supplier]
 * (`V4__ecosystem_tables.sql`, docs/DDD_Design.md Section 10.4). The
 * Kotlin object/class is renamed (2026-10-03, Creditor->Supplier domain
 * rename) but the underlying physical Postgres table name stays
 * "creditors" deliberately - a live table rename is its own separate
 * ForceNew-shaped risk, deferred to docs/Downtime_Maintenance_Backlog.md
 * same as the Education Runtime rename's AWS/DB resource names.
 */
object SuppliersTable : Table("creditors") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val name = varchar("name", 255)
    val currency = varchar("currency", 3)
    val balanceAmount = decimal("balance_amount", 19, 4)

    override val primaryKey = PrimaryKey(id)
}
