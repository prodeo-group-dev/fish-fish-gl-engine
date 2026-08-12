package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.Money
import java.math.BigDecimal

/**
 * One line of a PurchaseOrder - what's being bought and which
 * expense/asset Account it debits when the order is sent. Confirmed
 * 2026-08-12: a [GOODS][LineItemType.GOODS] line requires
 * [quantity]/[stockItemId] (so `PurchaseOrder.send()` knows what to
 * receive into Inventory - unit cost is derived as `amount / quantity`,
 * no separate unit-price field); a [SERVICE][LineItemType.SERVICE] line
 * forbids both, same conditional-field pattern as `SalesOrderLine`.
 */
data class PurchaseOrderLine(
    val description: String,
    val accountId: AccountId,
    val amount: Money,
    val itemType: LineItemType,
    val quantity: BigDecimal? = null,
    val stockItemId: StockItemId? = null
) {
    init {
        if (itemType == LineItemType.GOODS) {
            require(quantity != null && quantity.signum() > 0) {
                "A GOODS PurchaseOrderLine must have a positive quantity"
            }
            require(stockItemId != null) { "A GOODS PurchaseOrderLine must reference a StockItem" }
        } else {
            require(quantity == null) { "A SERVICE PurchaseOrderLine cannot have a quantity" }
            require(stockItemId == null) { "A SERVICE PurchaseOrderLine cannot reference a StockItem" }
        }
    }
}
