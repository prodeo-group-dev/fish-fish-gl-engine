package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Money the Company itself has borrowed - a bank loan, wholesale funding,
 * an overdraft facility - IAS 1 (current/non-current liability
 * presentation, via the caller's own `Account.classification` choice on
 * the Loan Payable account, same mechanism `WorkingCapital` already
 * reads generically) + IFRS 9 (financial liability recognition and
 * interest measurement). Closes the "Short-Term Borrowings" gap flagged
 * in Working Capital's IFRS grounding (docs/DDD_Design.md Section 2.1).
 *
 * **Not the same concept as Section 2.9's Borrower/Lender Party-Role
 * pair** (which models Purse *lending to* members, deferred to the
 * separate Lending system - docs/DDD_Design.md, `project_lending_separation`).
 * This is the mirror direction: the Company itself as the borrower, from
 * a bank or other funder - ordinary corporate liability accounting every
 * GL Engine tenant needs, not Purse-specific, and squarely in Ledger
 * scope like `Creditor`/`FixedAsset`.
 *
 * Deliberately mirrors `FixedAsset`'s shape for the recurring charge
 * ([recordInterestAccrual] : one call = one year's interest at
 * [annualInterestRate], not a day-count-convention proration - same
 * "callers decide their own cadence" discipline as
 * `FixedAsset.recordDepreciation()`), and `Creditor`'s shape for
 * settlement (`recordInterestPayment`/[recordPrincipalRepayment] cap at
 * the outstanding balance and post directly, no calling
 * `JournalEntry.reverse()` on a stored entry).
 *
 * [create] doesn't post the initial drawdown - matches `FixedAsset`
 * (acquisition isn't posted by `FixedAsset` either) and `Prepayment`
 * (the original payment isn't posted by `Prepayment`): the cash
 * movement that brought the loan into existence happens through a
 * separate mechanism (e.g. `CashBookEntry`, tagged
 * `CashFlowActivity.FINANCING`), and this aggregate only tracks what
 * happens afterward.
 *
 * Interest accrual/payment and principal repayment are deliberately
 * **three separate methods, each producing its own `JournalEntry`**,
 * not one compound entry combining principal and interest in a single
 * payment - a real loan repayment often *does* combine both in one bank
 * transaction, but bundling them into one cash line would make it
 * impossible to correctly tag that line with a single
 * `CashFlowActivity` (principal repayment is IAS 7 Financing; interest
 * paid is Operating). Keeping them as separate postings preserves
 * `StatementOfCashFlows`'s categorization - the whole reason
 * `DimensionType.CASH_FLOW_ACTIVITY` tags the cash line itself, not a
 * counter-account.
 */
class Borrowing private constructor(
    val id: BorrowingId,
    val companyId: CompanyId,
    val lenderName: String,
    val principal: Money,
    val annualInterestRate: BigDecimal,
    val originationDate: LocalDate
) {
    var outstandingPrincipal: Money = principal
        private set

    var accruedInterestPayable: Money = Money(BigDecimal.ZERO, principal.currency)
        private set

    /**
     * Accrues one year's interest on [outstandingPrincipal] at
     * [annualInterestRate]: debits [interestExpenseAccountId], credits
     * [accruedInterestPayableAccountId]. `JournalSource.SYSTEM`, matching
     * `FixedAsset.recordDepreciation()`'s automated-posting precedent.
     *
     * Returns `null` if fully repaid (no principal left to accrue
     * interest on) or the computed interest is zero (e.g. a 0% rate).
     */
    fun recordInterestAccrual(
        interestExpenseAccountId: AccountId,
        accruedInterestPayableAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (outstandingPrincipal.amount.signum() <= 0) return null
        val interest = outstandingPrincipal * annualInterestRate
        if (interest.amount.signum() <= 0) return null

        accruedInterestPayable = accruedInterestPayable + interest

        val lines = listOf(
            JournalLine(interestExpenseAccountId, interest, TransactionSide.DEBIT),
            JournalLine(accruedInterestPayableAccountId, interest, TransactionSide.CREDIT)
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.SYSTEM,
            "Interest accrual - $lenderName ($id)", journalEntryId
        )
    }

    /**
     * Pays down accrued interest: debits [accruedInterestPayableAccountId],
     * credits [cashAccountId]. Capped at [accruedInterestPayable], same
     * "capture the rounding remainder in full" discipline as
     * `Prepayment.recordRelease()`. The cash line is tagged
     * `CashFlowActivity.OPERATING` (IAS 7 permits either Operating or
     * Financing for interest paid, applied consistently - Operating
     * matches this codebase's existing default, e.g. `CashBookEntry`).
     *
     * Returns `null` if [amount] is non-positive or nothing is owed.
     */
    fun recordInterestPayment(
        amount: Money,
        cashAccountId: AccountId,
        accruedInterestPayableAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (amount.amount.signum() <= 0) return null
        if (accruedInterestPayable.amount.signum() <= 0) return null
        val payment = if (amount > accruedInterestPayable) accruedInterestPayable else amount

        accruedInterestPayable = accruedInterestPayable - payment

        val lines = listOf(
            JournalLine(accruedInterestPayableAccountId, payment, TransactionSide.DEBIT),
            JournalLine(
                cashAccountId, payment, TransactionSide.CREDIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
            )
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.MANUAL,
            "Interest payment - $lenderName ($id)", journalEntryId
        )
    }

    /**
     * Repays principal: debits [loanPayableAccountId], credits
     * [cashAccountId]. Capped at [outstandingPrincipal]. The cash line
     * is tagged `CashFlowActivity.FINANCING` (IAS 7, unambiguous -
     * unlike interest, principal repayment is always Financing).
     *
     * Returns `null` if [amount] is non-positive or already fully repaid.
     */
    fun recordPrincipalRepayment(
        amount: Money,
        cashAccountId: AccountId,
        loanPayableAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (amount.amount.signum() <= 0) return null
        if (outstandingPrincipal.amount.signum() <= 0) return null
        val repayment = if (amount > outstandingPrincipal) outstandingPrincipal else amount

        outstandingPrincipal = outstandingPrincipal - repayment

        val lines = listOf(
            JournalLine(loanPayableAccountId, repayment, TransactionSide.DEBIT),
            JournalLine(
                cashAccountId, repayment, TransactionSide.CREDIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.FINANCING.name)
            )
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.MANUAL,
            "Principal repayment - $lenderName ($id)", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            lenderName: String,
            principal: Money,
            annualInterestRate: BigDecimal,
            originationDate: LocalDate,
            id: BorrowingId = BorrowingId.generate()
        ): Borrowing {
            require(principal.amount.signum() > 0) { "Principal must be positive" }
            require(annualInterestRate.signum() >= 0) { "Interest rate cannot be negative" }
            return Borrowing(id, companyId, lenderName, principal, annualInterestRate, originationDate)
        }
    }
}
