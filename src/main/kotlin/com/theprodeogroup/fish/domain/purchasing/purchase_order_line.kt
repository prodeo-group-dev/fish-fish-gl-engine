package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.Money

/**
 * One line of a PurchaseOrder - what's being bought and which
 * expense/asset Account it debits when the order is sent. Deliberately
 * minimal for this increment: no quantity/unit-price breakdown, no tax -
 * just a description and a total line amount.
 */
data class PurchaseOrderLine(
    val description: String,
    val accountId: AccountId,
    val amount: Money
)
