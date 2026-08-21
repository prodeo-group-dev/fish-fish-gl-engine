package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * An upfront payment covering a future period - IAS 1 (a current asset,
 * not an immediate expense) + IFRS 15 (the expense is recognized as the
 * service/coverage is consumed, not when cash is paid). Closes a gap
 * flagged in Working Capital's IFRS grounding (docs/DDD_Design.md
 * Section 2.1): no "Prepaid Expense" concept existed anywhere, so
 * something like a year's insurance paid upfront would have posted
 * straight to expense, which is wrong.
 *
 * Deliberately mirrors `FixedAsset`'s straight-line shape almost exactly
 * ([amount]/[amountReleased]/[remainingBalance] <-> cost/accumulated
 * depreciation/netBookValue, [numberOfPeriods] <-> usefulLifeYears) -
 * same "one call = one period's release, not a calendar-date
 * proration, callers decide their own cadence" discipline as
 * `FixedAsset.recordDepreciation()`.
 */
class Prepayment private constructor(
    val id: PrepaymentId,
    val companyId: CompanyId,
    val description: String,
    val amount: Money,
    val paymentDate: LocalDate,
    val numberOfPeriods: Int
) {
    var amountReleased: Money = Money(BigDecimal.ZERO, amount.currency)
        private set

    val remainingBalance: Money
        get() = amount - amountReleased

    /** Straight-line: amount / numberOfPeriods. */
    val periodicReleaseAmount: Money
        get() = amount / BigDecimal(numberOfPeriods)

    /**
     * Releases one period's worth of the prepayment to expense: debits
     * [expenseAccountId], credits [prepaidAssetAccountId], using
     * `JournalSource.SYSTEM` (an automated period-end release, same
     * source as `FixedAsset.recordDepreciation()`).
     *
     * The release is capped at [remainingBalance], so a straight-line
     * division that doesn't divide evenly (e.g. £1000 over 3 periods)
     * has its rounding remainder captured in full by whichever call
     * crosses it, same as `FixedAsset.recordDepreciation()`. Returns
     * `null` if already fully released.
     */
    fun recordRelease(
        expenseAccountId: AccountId,
        prepaidAssetAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        val remaining = remainingBalance
        if (remaining.amount.signum() <= 0) return null
        val release = if (periodicReleaseAmount > remaining) remaining else periodicReleaseAmount

        amountReleased = amountReleased + release

        val lines = listOf(
            JournalLine(expenseAccountId, release, TransactionSide.DEBIT),
            JournalLine(prepaidAssetAccountId, release, TransactionSide.CREDIT)
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.SYSTEM,
            "Prepayment release - $description ($id)", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            description: String,
            amount: Money,
            paymentDate: LocalDate,
            numberOfPeriods: Int,
            id: PrepaymentId = PrepaymentId.generate()
        ): Prepayment {
            require(amount.amount.signum() > 0) { "Amount must be positive" }
            require(numberOfPeriods > 0) { "Number of periods must be positive" }
            return Prepayment(id, companyId, description, amount, paymentDate, numberOfPeriods)
        }
    }
}
