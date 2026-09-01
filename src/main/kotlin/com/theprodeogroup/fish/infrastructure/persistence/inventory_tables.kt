package com.theprodeogroup.fish.infrastructure.persistence

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

// StockItemsTable (backing GL's own legacy StockItem aggregate) was
// removed from this file 2026-09-01 ("Retire GL's StockItem from its
// legacy costing") - IM is the source of truth for inventory now. The
// underlying `stock_items` Postgres table and its existing rows are
// untouched (no migration dropped it) - only this Kotlin schema
// description, and the code that read/wrote through it, are gone.

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
