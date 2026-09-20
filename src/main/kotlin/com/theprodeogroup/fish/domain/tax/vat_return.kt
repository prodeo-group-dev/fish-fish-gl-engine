package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/** Whether a [VatReturn]'s net figure is owed to Revenue or reclaimable from it - never a raw signed number a caller has to reinterpret. */
enum class VatReturnDirection {
    PAYABLE,
    RECLAIMABLE
}

/**
 * Output VAT minus input VAT over a [VatFilingPeriod] - VAT's own
 * computation path (docs/IE/IE_VAT_MVP_Design.md #5), deliberately NOT
 * via `TaxComputation.of()`/`ProfitAndLoss` (the wrong shape: VAT is due
 * on gross transaction value over an arbitrary date range, not a Period's
 * net profit). Reads directly off the single netting VAT Control
 * Account's posted lines (Decision 2: credited by output VAT, debited by
 * input VAT) - the account's own net movement for the window *is* the
 * return figure, no separate reconciliation step needed.
 *
 * **Persisted, immutable once computed** - same audit/compliance
 * reasoning as [TaxComputation]'s own KDoc: a filed figure must be
 * preserved as it was actually computed, not silently replaced by a
 * later recomputation after more entries post to the same window.
 *
 * A negative [netVatDue] (input > output) is a real, expected refund
 * position for a VAT-registered business, not an error - [direction]
 * makes that explicit rather than leaving a caller to reinterpret a
 * signed number.
 */
class VatReturn private constructor(
    val id: VatReturnId,
    val companyId: CompanyId,
    val filingPeriod: VatFilingPeriod,
    val vatControlAccountId: AccountId,
    val outputVat: Money,
    val inputVat: Money,
    val netVatDue: Money,
    val direction: VatReturnDirection,
    val categoryBreakdown: Map<VatCategory, Money>,
    val computedAt: Instant
) {
    companion object {
        /**
         * [postedEntries] is the same shape [TaxComputation.of] already
         * takes - the caller supplies whatever set of entries is relevant
         * (typically every entry across however many GL `Period`s the
         * filing window spans, since a VAT filing period is independent
         * of accounting `Period` boundaries - [VatFilingPeriod]'s own
         * KDoc). Filtering to the window's actual date range and to
         * lines on [vatControlAccountId] happens here, not on the
         * caller.
         *
         * **Only entries whose status [PostingStatus.affectsBalance]
         * count** - a Draft/Pending/Rejected entry hasn't taken effect
         * yet, the same filter `ProfitAndLoss`/every other Ledger-derived
         * report already applies implicitly by only ever being handed
         * posted entries; made explicit here since a VAT return's window
         * can span multiple Periods with different closing states.
         *
         * A line on [vatControlAccountId] with no `DimensionType.VAT_CATEGORY`
         * tag still counts toward [outputVat]/[inputVat]/[netVatDue] (the
         * account's total balance movement is the return figure
         * regardless of tagging completeness) but is excluded from
         * [categoryBreakdown] - an untagged posting can't be attributed
         * to a category it never recorded, only counted.
         */
        fun of(
            companyId: CompanyId,
            filingPeriod: VatFilingPeriod,
            vatControlAccountId: AccountId,
            postedEntries: List<JournalEntry>,
            currency: Currency,
            id: VatReturnId = VatReturnId.generate(),
            now: Instant = Instant.now()
        ): VatReturn {
            val zero = Money(BigDecimal.ZERO, currency)

            val vatLines = postedEntries
                .filter { it.status.affectsBalance() && it.date in filingPeriod }
                .flatMap { entry -> entry.lines.filter { it.accountId == vatControlAccountId } }

            val outputVat = vatLines.filter { it.side == TransactionSide.CREDIT }
                .fold(zero) { sum, line -> sum + line.amount }
            val inputVat = vatLines.filter { it.side == TransactionSide.DEBIT }
                .fold(zero) { sum, line -> sum + line.amount }
            val netVatDue = outputVat - inputVat
            val direction = if (netVatDue.amount.signum() >= 0) VatReturnDirection.PAYABLE else VatReturnDirection.RECLAIMABLE

            val categoryBreakdown = vatLines
                .mapNotNull { line ->
                    val categoryName = line.dimensions[DimensionType.VAT_CATEGORY] ?: return@mapNotNull null
                    val category = VatCategory.valueOf(categoryName)
                    val signedAmount = if (line.side == TransactionSide.CREDIT) line.amount else zero - line.amount
                    category to signedAmount
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, amounts) -> amounts.fold(zero) { sum, amount -> sum + amount } }

            return VatReturn(
                id, companyId, filingPeriod, vatControlAccountId,
                outputVat, inputVat, netVatDue, direction, categoryBreakdown, now
            )
        }

        /** Rebuilds an already-computed VatReturn from persisted data - `internal`, matches every other aggregate's `reconstitute()` precedent. */
        internal fun reconstitute(
            id: VatReturnId,
            companyId: CompanyId,
            filingPeriod: VatFilingPeriod,
            vatControlAccountId: AccountId,
            outputVat: Money,
            inputVat: Money,
            netVatDue: Money,
            direction: VatReturnDirection,
            categoryBreakdown: Map<VatCategory, Money>,
            computedAt: Instant
        ): VatReturn = VatReturn(
            id, companyId, filingPeriod, vatControlAccountId,
            outputVat, inputVat, netVatDue, direction, categoryBreakdown, computedAt
        )
    }
}
