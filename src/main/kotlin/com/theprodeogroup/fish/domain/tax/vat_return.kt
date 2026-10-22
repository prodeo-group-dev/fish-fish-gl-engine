package com.theprodeogroup.fish.domain.tax

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.Instant
import java.util.Currency

/**
 * The side of a VAT return a line on the VAT Control Account belongs to, carried by the `DimensionType.VAT_DIRECTION`
 * tag (2026-10-15). It exists for adjustments, which sit on the opposite debit/credit side to the figure they adjust:
 * a debit tagged [OUTPUT] reduces output VAT (a sales credit note), a credit tagged [INPUT] reduces input VAT (a
 * discount received from a supplier).
 *
 * [SETTLEMENT] (2026-10-22, GL-T-30, GL-FR-TAX-9) marks the payment of VAT to the authority, or a refund from it: the cash
 * moving the balance of the VAT control account, which is not VAT charged or suffered. A return **excludes** such lines, or
 * the quarter a remittance is paid in would read it as input VAT and understate its own net due.
 */
enum class VatLineDirection {
    OUTPUT,
    INPUT,
    SETTLEMENT;

    companion object {
        /** The direction a line's tag names, or null if untagged or tagged with anything else. */
        fun of(tag: String?): VatLineDirection? = entries.firstOrNull { it.name == tag }
    }
}

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
         * **What the return is, and when it equals the account (GL-T-30, Femi 2026-10-22).** The return is the **movement** on
         * [vatControlAccountId] over the filing dates (credits less debits), not the account's balance. Paying the net due clears
         * the account only if (a) the balance at the start of the period was zero (each earlier period fully paid), (b) no untagged
         * movement other than settlements was posted (a manual journal or an opening figure on the account is counted by its
         * side), and (c) the payments themselves are excluded, which is what the `SETTLEMENT` tag does. Until a sales return posts a
         * VAT reversal (GL-T-10) output VAT is overstated by the VAT on returns. A difference between the return and the account
         * is a reconciling item to list, never to hide (`GL_SubLedger_TieOut_Design.md`).
         *
         * A line on [vatControlAccountId] with no `DimensionType.VAT_CATEGORY`
         * tag still counts toward [outputVat]/[inputVat]/[netVatDue] (the
         * account's total balance movement is the return figure
         * regardless of tagging completeness) but is excluded from
         * [categoryBreakdown] - an untagged posting can't be attributed
         * to a category it never recorded, only counted.
         *
         * **VAT_DIRECTION (2026-10-15):** [outputVat] and [inputVat] are classified by the line's `DimensionType.VAT_DIRECTION`
         * tag when it is `OUTPUT` or `INPUT`, otherwise by side (credit = output, debit = input), exactly as before. A
         * tagged adjustment reduces its own side: a debit tagged OUTPUT reduces [outputVat], a credit tagged INPUT
         * reduces [inputVat]. [netVatDue] and [categoryBreakdown] are credits less debits and never depend on the tag.
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
                .filter { VatLineDirection.of(it.dimensions[DimensionType.VAT_DIRECTION]) != VatLineDirection.SETTLEMENT }

            // A line's return side: its VAT_DIRECTION tag if it has a valid one, else its debit/credit side (credit =
            // output, debit = input), exactly as before the tag existed. An adjustment (a debit tagged OUTPUT, a credit
            // tagged INPUT) reduces its own side instead of growing the other one. Net is credits less debits either way.
            fun directionOf(line: JournalLine): VatLineDirection =
                VatLineDirection.of(line.dimensions[DimensionType.VAT_DIRECTION])
                    ?: if (line.side == TransactionSide.CREDIT) VatLineDirection.OUTPUT else VatLineDirection.INPUT
            fun signedForSide(line: JournalLine): Money =
                // The line increases its side when its debit/credit side is the natural one for that side.
                if ((directionOf(line) == VatLineDirection.OUTPUT) == (line.side == TransactionSide.CREDIT)) line.amount else zero - line.amount

            val outputVat = vatLines.filter { directionOf(it) == VatLineDirection.OUTPUT }
                .fold(zero) { sum, line -> sum + signedForSide(line) }
            val inputVat = vatLines.filter { directionOf(it) == VatLineDirection.INPUT }
                .fold(zero) { sum, line -> sum + signedForSide(line) }
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
