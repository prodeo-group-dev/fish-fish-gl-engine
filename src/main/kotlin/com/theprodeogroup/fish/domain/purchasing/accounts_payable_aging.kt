package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AgingBucketAmount
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.hasHistoricalEffect
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Currency

/**
 * A Creditor's outstanding payables bucketed by age (docs/DDD_Design.md
 * Section 2.5) - the AP mirror of `AccountsReceivableAging` (`domain.sales`),
 * same derivation from already-posted `JournalEntry` data via the
 * `DimensionType.VENDOR` tag and `Creditor.makePayment()` actually
 * posting payments to the Ledger (both fixed 2026-08-12).
 *
 * **Sides are swapped from `AccountsReceivableAging`, not copy-pasted by
 * mistake.** AP is a liability: a charge (`PurchaseOrder.send()`)
 * *credits* the AP control account; a payment (`Creditor.makePayment()`)
 * *debits* it - the exact mirror image of AR, where a sale debits and a
 * receipt credits. Same FIFO/oldest-first allocation caveat as
 * `AccountsReceivableAging` - `Creditor.balance` is "balance forward,"
 * not "open item," so payments aren't tied to specific charges.
 */
class AccountsPayableAging private constructor(
    val creditorId: CreditorId,
    val asOfDate: LocalDate,
    val currency: Currency,
    val buckets: List<AgingBucketAmount>
) {
    val totalOutstanding: Money
        get() = buckets.fold(Money(BigDecimal.ZERO, currency)) { sum, bucket -> sum + bucket.amount }

    companion object {
        fun of(
            creditorId: CreditorId,
            apControlAccountId: AccountId,
            postedEntries: List<JournalEntry>,
            asOfDate: LocalDate,
            currency: Currency
        ): AccountsPayableAging {
            val creditorTag = creditorId.value.toString()
            val zero = Money(BigDecimal.ZERO, currency)

            val relevantEntries = postedEntries.filter { it.status.hasHistoricalEffect() }

            val charges = relevantEntries
                .flatMap { entry -> entry.lines.map { entry.date to it } }
                .filter { (_, line) ->
                    line.accountId == apControlAccountId &&
                        line.side == TransactionSide.CREDIT &&
                        line.dimensions[DimensionType.VENDOR] == creditorTag
                }
                .sortedBy { (date, _) -> date }

            val totalPayments = relevantEntries
                .flatMap { it.lines }
                .filter {
                    it.accountId == apControlAccountId &&
                        it.side == TransactionSide.DEBIT &&
                        it.dimensions[DimensionType.VENDOR] == creditorTag
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
            return AccountsPayableAging(creditorId, asOfDate, currency, buckets)
        }
    }
}
