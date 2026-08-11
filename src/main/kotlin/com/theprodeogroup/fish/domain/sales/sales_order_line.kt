package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.Money

/**
 * One line of a SalesOrder - what's being sold and which Revenue Account
 * it credits when *that line* is delivered. Deliberately minimal for
 * this increment: no quantity/unit-price breakdown, no tax, no
 * point-in-time vs. over-time (IFRS 15) distinction - see
 * `SalesOrder.deliverLine()`'s KDoc for why delivery is modeled per-line
 * rather than as arbitrary partial amounts.
 */
data class SalesOrderLine(
    val description: String,
    val accountId: AccountId,
    val amount: Money
)
