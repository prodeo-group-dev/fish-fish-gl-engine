package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * What the Company is owed and what it owes, with the part that is "overdue", for the Business Owner dashboard
 * (docs/ea-dashboard-playbook; Femi, 2026-10-12: overdue means unpaid and older than 30 days, labelled as such).
 *
 * Built from the aging items of every customer (receivables) and supplier (payables): `total` is every unpaid amount,
 * `overdueTotal` the unpaid amounts outside the CURRENT bucket (older than 30 days since the sale or purchase date,
 * after the first-in-first-out allocation of receipts and payments), `overdueCount` the number of unpaid sale or
 * purchase lines in those older buckets (a part-paid line counts once). It is age since the transaction, not past a
 * due date: GL holds no payment terms.
 */
class ReceivablesPayablesSummary private constructor(
    val currency: Currency,
    val asOf: LocalDate,
    val receivables: Side,
    val payables: Side
) {
    data class Side(val total: Money, val overdueTotal: Money, val overdueCount: Int) {
        companion object {
            fun of(items: List<UnpaidItem>, currency: Currency): Side {
                val zero = Money(BigDecimal.ZERO, currency)
                val overdue = items.filter { it.bucket != AgingBucketLabel.CURRENT }
                return Side(
                    total = items.fold(zero) { sum, item -> sum + item.amount },
                    overdueTotal = overdue.fold(zero) { sum, item -> sum + item.amount },
                    overdueCount = overdue.size
                )
            }
        }
    }

    companion object {
        fun of(receivableItems: List<UnpaidItem>, payableItems: List<UnpaidItem>, asOf: LocalDate, currency: Currency) =
            ReceivablesPayablesSummary(currency, asOf, Side.of(receivableItems, currency), Side.of(payableItems, currency))
    }
}
