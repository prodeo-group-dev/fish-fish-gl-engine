package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AgingBucketAmount
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.UnpaidItem
import com.theprodeogroup.fish.domain.ledger.fifoUnpaidItems
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
    val buckets: List<AgingBucketAmount>,
    /** The unpaid items the [buckets] are made of (see [UnpaidItem]); oldest first. */
    val unpaidItems: List<UnpaidItem> = emptyList()
) {
    val totalOutstanding: Money
        get() = buckets.fold(Money(BigDecimal.ZERO, currency)) { sum, bucket -> sum + bucket.amount }

    companion object {
        /**
         * The unpaid payables on the control account that belong to NO known supplier: lines with no supplier tag (an
         * inventory receipt credits Accounts Payable without one), or a tag [isKnownSupplierTag] does not recognise. One
         * anonymous party, first in first out among themselves, aged by their own dates, so a dashboard total can agree
         * with the ledger's Accounts Payable balance.
         */
        fun unattributedUnpaidItems(
            apControlAccountId: AccountId,
            postedEntries: List<JournalEntry>,
            asOfDate: LocalDate,
            currency: Currency,
            isKnownSupplierTag: (String) -> Boolean
        ): List<UnpaidItem> {
            val zero = Money(BigDecimal.ZERO, currency)
            val lines = postedEntries.filter { it.status.hasHistoricalEffect() }
                .flatMap { entry -> entry.lines.map { entry.date to it } }
                .filter { (_, line) ->
                    line.accountId == apControlAccountId && line.dimensions[DimensionType.VENDOR].let { it == null || !isKnownSupplierTag(it) }
                }
            val charges = lines.filter { (_, line) -> line.side == TransactionSide.CREDIT }.map { (date, line) -> date to line.amount }
            val payments = lines.filter { (_, line) -> line.side == TransactionSide.DEBIT }.fold(zero) { sum, (_, line) -> sum + line.amount }
            return fifoUnpaidItems(charges, payments, asOfDate, currency)
        }

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

            val unpaidItems = unpaidCharges.map { (date, amount) ->
                UnpaidItem(date, amount, AgingBucketLabel.forDaysOld(ChronoUnit.DAYS.between(date, asOfDate)))
            }
            val bucketTotals = AgingBucketLabel.entries.associateWith { zero }.toMutableMap()
            for (item in unpaidItems) {
                bucketTotals[item.bucket] = bucketTotals.getValue(item.bucket) + item.amount
            }

            val buckets = AgingBucketLabel.entries.map { AgingBucketAmount(it, bucketTotals.getValue(it)) }
            return AccountsPayableAging(supplierId, asOfDate, currency, buckets, unpaidItems)
        }
    }
}
