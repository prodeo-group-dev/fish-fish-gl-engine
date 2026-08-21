package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.date

/**
 * Exposed table definitions for Purchase Order Processing
 * (`V4__ecosystem_tables.sql`, docs/DDD_Design.md Section 10.4) - matches
 * the migration's DDL exactly.
 */
object CreditorsTable : Table("creditors") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val name = varchar("name", 255)
    val currency = varchar("currency", 3)
    val balanceAmount = decimal("balance_amount", 19, 4)

    override val primaryKey = PrimaryKey(id)
}

object PurchaseOrdersTable : Table("purchase_orders") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val creditorId = uuid("creditor_id")
    val orderDate = date("order_date")
    /** Widened from VARCHAR(20) - GOODS_RECEIVED_PENDING_INVOICE (30 chars) exceeds the original DRAFT/SENT sizing, `V7__purchase_order_delivery_terms.sql`. */
    val status = varchar("status", 30)

    /**
     * Goods-in-Transit/GRNI columns, added 2026-08-21
     * (`docs/IFRS_GL_Posting_Matrix.md`'s Goods-in-Transit/GRNI gap,
     * `V7__purchase_order_delivery_terms.sql`). [deliveryTerms] defaults
     * to `CONTROL_TRANSFERS_AT_SHIPMENT` at the SQL level too, so every
     * pre-existing row keeps `PurchaseOrder.send`'s original behaviour
     * on reload. [goodsInTransitAccountId]/[grniAccountId] are mutually
     * exclusive in practice (an order only ever follows one of the two
     * `DeliveryTerms.CONTROL_TRANSFERS_AT_RECEIPT` paths) but both
     * nullable rather than one shared column, matching the domain
     * model's own two separate fields.
     */
    val deliveryTerms = varchar("delivery_terms", 30)
    val goodsInTransitAccountId = uuid("goods_in_transit_account_id").nullable()
    val grniAccountId = uuid("grni_account_id").nullable()

    override val primaryKey = PrimaryKey(id)
}

/**
 * One row per `PurchaseOrderLine` - a plain `data class` with no
 * identity of its own in the domain model, same treatment as
 * `journal_lines` (Section 10.2). `(purchase_order_id, line_index)` is a
 * synthetic key preserving line order.
 */
object PurchaseOrderLinesTable : Table("purchase_order_lines") {
    val purchaseOrderId = uuid("purchase_order_id")
    val lineIndex = integer("line_index")
    val description = varchar("description", 255)
    val accountId = uuid("account_id")
    val amount = decimal("amount", 19, 4)
    val currency = varchar("currency", 3)
    val itemType = varchar("item_type", 10)
    val quantity = decimal("quantity", 19, 4).nullable()
    val stockItemId = uuid("stock_item_id").nullable()

    override val primaryKey = PrimaryKey(purchaseOrderId, lineIndex)
}
