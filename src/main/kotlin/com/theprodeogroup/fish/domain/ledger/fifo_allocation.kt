package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * First-in-first-out allocation of settlements (receipts or payments) against charges (sales or purchases), oldest
 * charge first: what is left unpaid, each with the bucket its age puts it in. A part-paid charge is one item for its
 * remainder. Shared by the receivables and payables aging so the rule exists once.
 */
internal fun fifoUnpaidItems(
    charges: List<Pair<LocalDate, Money>>,
    totalSettlements: Money,
    asOfDate: LocalDate,
    currency: Currency
): List<UnpaidItem> {
    val zero = Money(BigDecimal.ZERO, currency)
    var remaining = totalSettlements
    val unpaid = mutableListOf<Pair<LocalDate, Money>>()
    for ((date, amount) in charges.sortedBy { it.first }) {
        when {
            remaining >= amount -> remaining -= amount
            remaining.amount.signum() > 0 -> {
                unpaid.add(date to (amount - remaining))
                remaining = zero
            }
            else -> unpaid.add(date to amount)
        }
    }
    return unpaid.map { (date, amount) -> UnpaidItem(date, amount, AgingBucketLabel.forDaysOld(ChronoUnit.DAYS.between(date, asOfDate))) }
}
