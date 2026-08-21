package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.LocalDate

/**
 * An expense incurred but not yet invoiced/paid - IAS 1 (recognized as a
 * current liability, not deferred to when cash moves) + IFRS 9 (the
 * liability's own recognition/measurement). Closes a gap flagged in
 * Working Capital's IFRS grounding (docs/DDD_Design.md Section 2.1).
 *
 * The underlying accounting mechanic (post an estimate, reverse it next
 * period, then post the real invoice normally) was already fully
 * supported before this was built - `JournalEntry.reverse()` and
 * `JournalSource.SYSTEM`'s own doc comment ("Automatic accruals") already
 * covered it. This aggregate doesn't replace that mechanic; it wraps it
 * with queryable state ([isOutstanding]) so a caller can find which
 * accruals are still awaiting reversal - something a raw list of
 * `JournalEntry` can't answer, since nothing on `JournalEntry` itself
 * distinguishes "this was an accrual" from any other reversible entry.
 *
 * The mirror image of `Prepayment` in direction, not in shape: a
 * Prepayment is cash paid now, expense recognized progressively later
 * (an asset that releases over many periods); an AccruedExpense is
 * expense recognized now, cash paid later (a liability settled by a
 * single reversal, not a multi-period schedule) - so [postAccrual]/
 * [reverseAccrual] are two one-time actions, not `Prepayment`'s repeated
 * `recordRelease()`. Each posts its own lines directly (mirroring
 * `FixedAsset.dispose()`'s precedent) rather than calling
 * `JournalEntry.reverse()` on a stored entry, since aggregates in this
 * codebase never hold another aggregate's object, only ever return one.
 */
class AccruedExpense private constructor(
    val id: AccruedExpenseId,
    val companyId: CompanyId,
    val description: String,
    val amount: Money,
    val accrualDate: LocalDate
) {
    var isPosted: Boolean = false
        private set

    var isReversed: Boolean = false
        private set

    /** True once the accrual estimate has been posted but not yet reversed - what a caller queries to find outstanding accruals. */
    val isOutstanding: Boolean
        get() = isPosted && !isReversed

    /**
     * Posts the period-end accrual estimate: debits [expenseAccountId],
     * credits [accruedLiabilityAccountId], for the full [amount].
     * `JournalSource.SYSTEM`, matching `FixedAsset.recordDepreciation()`/
     * `Prepayment.recordRelease()`'s automated-posting precedent.
     *
     * Returns `null` if already posted - a one-time action.
     */
    fun postAccrual(
        expenseAccountId: AccountId,
        accruedLiabilityAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (isPosted) return null
        isPosted = true

        val lines = listOf(
            JournalLine(expenseAccountId, amount, TransactionSide.DEBIT),
            JournalLine(accruedLiabilityAccountId, amount, TransactionSide.CREDIT)
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.SYSTEM,
            "Accrual - $description ($id)", journalEntryId
        )
    }

    /**
     * Reverses the accrual at the start of the next period: debits
     * [accruedLiabilityAccountId], credits [expenseAccountId], for the
     * full [amount] - the mirror image of [postAccrual]'s lines. The
     * real invoice is then posted normally by the caller (e.g.
     * `PurchaseOrder.send()`), entirely outside this aggregate, same as
     * the standard "reverse the estimate, book the real thing" pattern
     * `JournalEntry.reverse()` already supports generically.
     *
     * `JournalSource.REVERSAL`, matching `JournalEntry.reverse()`'s own
     * convention for its generated reversal entry.
     *
     * Returns `null` if not yet posted, or already reversed.
     */
    fun reverseAccrual(
        expenseAccountId: AccountId,
        accruedLiabilityAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (!isPosted || isReversed) return null
        isReversed = true

        val lines = listOf(
            JournalLine(accruedLiabilityAccountId, amount, TransactionSide.DEBIT),
            JournalLine(expenseAccountId, amount, TransactionSide.CREDIT)
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.REVERSAL,
            "Accrual reversal - $description ($id)", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            description: String,
            amount: Money,
            accrualDate: LocalDate,
            id: AccruedExpenseId = AccruedExpenseId.generate()
        ): AccruedExpense {
            require(amount.amount.signum() > 0) { "Amount must be positive" }
            return AccruedExpense(id, companyId, description, amount, accrualDate)
        }
    }
}
