package com.theprodeogroup.fish.domain.sales

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
 * A Customer's outstanding receivables bucketed by age, derived entirely
 * from already-posted `JournalEntry` data (docs/DDD_Design.md Section
 * 2.5/2.1) - a query/report layer, no new domain invariants, same
 * pattern as `TrialBalance`/`WorkingCapital`. Made possible by tagging
 * AR/AP lines with `DimensionType.CUSTOMER`/`VENDOR` (fixed 2026-08-12 -
 * previously never populated anywhere) and by `Customer.receivePayment()`
 * actually posting receipts to the Ledger (the same fix, other side).
 *
 * **FIFO/oldest-first allocation, not per-invoice cash application.**
 * Nothing in this codebase ties a specific receipt to a specific sale -
 * `Customer.balance` is a "balance forward" running total, not an
 * "open item" ledger. So receipts are summed and applied against the
 * oldest unpaid sales first, a standard simplification for balance-
 * forward AR systems, not a stored fact - if a customer's payment was
 * actually intended for a specific later invoice, this will misattribute
 * it. This is the accepted tradeoff of not having invoice-level
 * allocation, not a bug.
 */
class AccountsReceivableAging private constructor(
    val customerId: CustomerId,
    val asOfDate: LocalDate,
    val currency: Currency,
    val buckets: List<AgingBucketAmount>
) {
    val totalOutstanding: Money
        get() = buckets.fold(Money(BigDecimal.ZERO, currency)) { sum, bucket -> sum + bucket.amount }

    companion object {
        fun of(
            customerId: CustomerId,
            arControlAccountId: AccountId,
            postedEntries: List<JournalEntry>,
            asOfDate: LocalDate,
            currency: Currency
        ): AccountsReceivableAging {
            val customerTag = customerId.value.toString()
            val zero = Money(BigDecimal.ZERO, currency)

            val relevantEntries = postedEntries.filter { it.status.hasHistoricalEffect() }

            val sales = relevantEntries
                .flatMap { entry -> entry.lines.map { entry.date to it } }
                .filter { (_, line) ->
                    line.accountId == arControlAccountId &&
                        line.side == TransactionSide.DEBIT &&
                        line.dimensions[DimensionType.CUSTOMER] == customerTag
                }
                .sortedBy { (date, _) -> date }

            val totalReceipts = relevantEntries
                .flatMap { it.lines }
                .filter {
                    it.accountId == arControlAccountId &&
                        it.side == TransactionSide.CREDIT &&
                        it.dimensions[DimensionType.CUSTOMER] == customerTag
                }
                .fold(zero) { sum, line -> sum + line.amount }

            var remainingReceipts = totalReceipts
            val unpaidSales = mutableListOf<Pair<LocalDate, Money>>()
            for ((date, line) in sales) {
                when {
                    remainingReceipts >= line.amount -> remainingReceipts -= line.amount
                    remainingReceipts.amount.signum() > 0 -> {
                        unpaidSales.add(date to (line.amount - remainingReceipts))
                        remainingReceipts = zero
                    }
                    else -> unpaidSales.add(date to line.amount)
                }
            }

            val bucketTotals = AgingBucketLabel.entries.associateWith { zero }.toMutableMap()
            for ((date, amount) in unpaidSales) {
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
            return AccountsReceivableAging(customerId, asOfDate, currency, buckets)
        }
    }
}
