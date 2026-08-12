package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.common.LineItemType
import com.theprodeogroup.fish.domain.inventory.StockItemId
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.Money
import java.math.BigDecimal

/**
 * One line of a SalesOrder - what's being sold and which Revenue Account
 * it credits when *that line* is delivered. Confirmed 2026-08-12: a
 * [GOODS][LineItemType.GOODS] line requires [quantity]/[stockItemId] (so
 * `SalesOrder.deliverLine()` knows what to issue from Inventory and post
 * COGS against); a [SERVICE][LineItemType.SERVICE] line forbids both,
 * matching `Account.create()`'s `requiresClassification()` precedent for
 * a type-conditional field requirement. Still no unit-price/tax
 * breakdown - [amount] remains the line total.
 */
data class SalesOrderLine(
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
                "A GOODS SalesOrderLine must have a positive quantity"
            }
            require(stockItemId != null) { "A GOODS SalesOrderLine must reference a StockItem" }
        } else {
            require(quantity == null) { "A SERVICE SalesOrderLine cannot have a quantity" }
            require(stockItemId == null) { "A SERVICE SalesOrderLine cannot reference a StockItem" }
        }
    }
}
