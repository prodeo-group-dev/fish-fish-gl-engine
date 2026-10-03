package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AgingBucketAmount
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * A Supplier's outstanding payables bucketed by age (docs/DDD_Design.md
 * Section 2.5) - the AP mirror of `AccountsReceivableAging` (`domain.sales`),
 * same derivation from already-posted `JournalEntry` data via the
 * `DimensionType.VENDOR` tag and `Supplier.makePayment()` actually
 * posting payments to the Ledger (both fixed 2026-08-12).
 *
 * **Sides are swapped from `AccountsReceivableAging`, not copy-pasted by
 * mistake.** AP is a liability: a charge (`PurchaseOrder.send()`)
 * *credits* the AP control account; a payment (`Supplier.makePayment()`)
 * *debits* it - the exact mirror image of AR, where a sale debits and a
 * receipt credits. Same FIFO/oldest-first allocation caveat as
 * `AccountsReceivableAging` - `Supplier.balance` is "balance forward,"
 * not "open item," so payments aren't tied to specific charges.
 */
class AccountsPayableAging private constructor(
    val supplierId: SupplierId,
    val asOfDate: LocalDate,
    val currency: Currency,
    val buckets: List<AgingBucketAmount>
) {
    val totalOutstanding: Money
        get() = buckets.fold(Money(BigDecimal.ZERO, currency)) { sum, bucket -> sum + bucket.amount }

    companion object {
        fun of(
            supplierId: SupplierId,
            apControlAccountId: AccountId,
            postedEntries: List<JournalEntry>,
            asOfDate: LocalDate,
            currency: Currency
        ): AccountsPayableAging {
            val supplierTag = supplierId.value.toString()
            val zero = Money(BigDecimal.ZERO, currency)

            val relevantEntries = postedEntries.filter { it.status.hasHistoricalEffect() }

            val charges = relevantEntries
                .flatMap { entry -> entry.lines.map { entry.date to it } }
                .filter { (_, line) ->
                    line.accountId == apControlAccountId &&
                        line.side == TransactionSide.CREDIT &&
                        line.dimensions[DimensionType.VENDOR] == supplierTag
                }
                .sortedBy { (date, _) -> date }

            val totalPayments = relevantEntries
                .flatMap { it.lines }
                .filter {
                    it.accountId == apControlAccountId &&
                        it.side == TransactionSide.DEBIT &&
                        it.dimensions[DimensionType.VENDOR] == supplierTag
                }
                .fold(zero) { sum, line -> sum + line.amount }

            var remainingPayments = totalPayments
            val unpaidCharges = mutableListOf<Pair<LocalDate, Money>>()
            for ((date, line) in charges) {
                when {
                    remainingPayments >= line.amount -> remainingPayments -= line.amount
                    remainingPayments.amount.signum() > 0 -> {
                        unpaidCharges.add(date to (line.amount - remainingPayments))
                        remainingPayments = zero
                    }
                    else -> unpaidCharges.add(date to line.amount)
                }
            }

            val bucketTotals = AgingBucketLabel.entries.associateWith { zero }.toMutableMap()
            for ((date, amount) in unpaidCharges) {
                val daysOverdue = ChronoUnit.DAYS.between(date, asOfDate)
                val label = when {
                    daysOverdue <= 30 -> AgingBucketLabel.CURRENT
                    daysOverdue <= 60 -> AgingBucketLabel.DAYS_31_TO_60
                    daysOverdue <= 90 -> AgingBucketLabel.DAYS_61_TO_90
                    else -> AgingBucketLabel.OVER_90
                }
                bucketTotals[label] = bucketTotals.getValue(label) + amount
            }

            val buckets = AgingBucketLabel.entries.map { AgingBucketAmount(it, bucketTotals.getValue(it)) }
            return AccountsPayableAging(supplierId, asOfDate, currency, buckets)
        }
    }
}
