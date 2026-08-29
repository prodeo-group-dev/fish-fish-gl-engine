package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/** Exposed table definition for Inventory Management (`V4__ecosystem_tables.sql`, docs/DDD_Design.md Section 10.4). */
object StockItemsTable : Table("stock_items") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val name = varchar("name", 255)
    val currency = varchar("currency", 3)
    val stage = varchar("stage", 20)
    val quantityOnHand = decimal("quantity_on_hand", 19, 4)
    val unitCostAmount = decimal("unit_cost_amount", 19, 4)
    val nrvWriteDownPerUnitAmount = decimal("nrv_write_down_per_unit_amount", 19, 4)

    override val primaryKey = PrimaryKey(id)
}

/**
 * Exposed table definition for [com.theprodeogroup.fish.domain.inventory.StockShortageEscalation]
 * (`V13__stock_shortage_escalations.sql`) - append-only, no update path.
 */
object StockShortageEscalationsTable : Table("stock_shortage_escalations") {
    val id = uuid("id")
    val companyId = uuid("company_id")
    val stockItemId = uuid("stock_item_id")
    val requestedQuantity = decimal("requested_quantity", 19, 4)
    val quantityOnHandAtRequest = decimal("quantity_on_hand_at_request", 19, 4)
    val requestedByEmail = varchar("requested_by_email", 255)
    val overridden = bool("overridden")
    val requestedAt = timestamp("requested_at")

    override val primaryKey = PrimaryKey(id)
}
