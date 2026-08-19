package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/**
 * Exposed table definitions for Sales Order Processing
 * (`V4__ecosystem_tables.sql`, docs/DDD_Design.md Section 10.4) - the AR
 * mirror of `purchasing_tables.kt`.
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

object SalesOrdersTable : Table("sales_orders") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val customerId = uuid("customer_id")
    val orderDate = date("order_date")

    override val primaryKey = PrimaryKey(id)
}

/** One row per `SalesOrderLine`, same synthetic-key treatment as `PurchaseOrderLinesTable`. */
object SalesOrderLinesTable : Table("sales_order_lines") {
    val salesOrderId = uuid("sales_order_id")
    val lineIndex = integer("line_index")
    val description = varchar("description", 255)
    val accountId = uuid("account_id")
    val amount = decimal("amount", 19, 4)
    val currency = varchar("currency", 3)
    val itemType = varchar("item_type", 10)
    val quantity = decimal("quantity", 19, 4).nullable()
    val stockItemId = uuid("stock_item_id").nullable()

    override val primaryKey = PrimaryKey(salesOrderId, lineIndex)
}

/**
 * Persists `SalesOrder`'s private `deliveredLineIndices` set - `status`
 * is *computed* from this set (see `SalesOrder`'s KDoc), not a stored
 * field of its own, so this table is the entirety of what needs
 * persisting beyond the order's lines themselves.
 */
object SalesOrderDeliveredLinesTable : Table("sales_order_delivered_lines") {
    val salesOrderId = uuid("sales_order_id")
    val lineIndex = integer("line_index")

    override val primaryKey = PrimaryKey(salesOrderId, lineIndex)
}
