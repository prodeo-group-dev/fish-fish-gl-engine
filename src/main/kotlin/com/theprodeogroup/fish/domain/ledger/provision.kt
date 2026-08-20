package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * A liability of uncertain timing or amount - IAS 37 (legal claims,
 * warranties, environmental remediation, restructuring). Genuinely
 * distinct from `AccruedExpense`, not a variant of it: an accrual is a
 * reasonably certain amount just not yet invoiced, while a Provision
 * exists precisely *because* the amount and/or timing is uncertain and
 * has to be estimated, then reviewed and re-estimated over time.
 *
 * IAS 37's three recognition criteria (present obligation from a past
 * event, probable outflow, reliable estimate) are a judgment call made
 * by the caller before ever calling [remeasure] with a positive target -
 * this aggregate doesn't evaluate them, the same "data, not code"
 * treatment already used for `TaxRule`/`PayrollDeductionLine`/ECL loss
 * rates: the *estimate* is a caller-supplied input, not something this
 * type computes from probabilities or scenarios.
 *
 * **Deliberately does not discount to present value.** IAS 37 requires
 * discounting when the time value of money is material (typically
 * long-term provisions), which would need an unwinding-of-discount
 * finance-cost line each period - no source doc defines a discount rate
 * methodology, so this isn't modeled. [balance] is assumed to already be
 * the amount the caller wants recognized (already discounted, if that
 * applies, before calling [remeasure]).
 *
 * [remeasure] deliberately mirrors `Customer.assessExpectedCreditLoss()`'s
 * target-and-delta shape (re-assess to a target each call, post only the
 * difference) rather than `FixedAsset.recordDepreciation()`'s pure
 * accumulation - IAS 37 provisions, like ECL, must be capable of
 * shrinking or fully reversing (the obligation may no longer be
 * probable), not just growing.
 */
class Provision private constructor(
    val id: ProvisionId,
    val companyId: CompanyId,
    val description: String,
    val currency: Currency
) {
    var balance: Money = Money(BigDecimal.ZERO, currency)
        private set

    /**
     * Re-measures the provision to [targetAmount] - the first call
     * (from a zero starting balance) is the initial recognition;
     * subsequent calls are the period-end reviews IAS 37 requires.
     * Posts only the delta between [targetAmount] and the current
     * [balance]: a top-up (debit expense/credit the provision) if the
     * target increased, a reversal (debit the provision/credit expense)
     * if it decreased - a target of zero fully reverses the provision
     * (the obligation is no longer probable, or was discharged without
     * a cash outflow). `JournalSource.SYSTEM`, matching
     * `Customer.assessExpectedCreditLoss()`'s automated-review
     * precedent.
     *
     * Returns `null` if the delta is exactly zero - nothing to post.
     */
    fun remeasure(
        targetAmount: Money,
        expenseAccountId: AccountId,
        provisionAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        val delta = targetAmount - balance
        if (delta.amount.signum() == 0) return null

        val lines = if (delta.amount.signum() > 0) {
            listOf(
                JournalLine(expenseAccountId, delta, TransactionSide.DEBIT),
                JournalLine(provisionAccountId, delta, TransactionSide.CREDIT)
            )
        } else {
            val reversalAmount = Money(BigDecimal.ZERO, currency) - delta
            listOf(
                JournalLine(provisionAccountId, reversalAmount, TransactionSide.DEBIT),
                JournalLine(expenseAccountId, reversalAmount, TransactionSide.CREDIT)
            )
        }

        balance = targetAmount
        return JournalEntry.create(
            periodId, date, lines, JournalSource.SYSTEM,
            "Provision remeasurement - $description ($id)", journalEntryId
        )
    }

    /**
     * Utilizes (settles) the provision when the obligation crystallizes
     * into an actual cash outflow: debits [provisionAccountId], credits
     * [cashAccountId]. IAS 37's "restricted use" requirement - an
     * expenditure may only be charged against the specific provision it
     * relates to - holds structurally, since this only ever reduces
     * *this* Provision instance's own [balance], never a shared pool.
     *
     * Capped at [balance], same "capture the remainder in full"
     * discipline as `Prepayment.recordRelease()`. The cash line is
     * tagged `CashFlowActivity.OPERATING` (settling a warranty claim,
     * legal settlement, etc. is an Operating outflow under IAS 7 in the
     * general case). If a provision is settled via an invoiced payable
     * first rather than direct cash, that's the existing generic
     * `Account`/`JournalEntry` mechanism, outside this method's scope.
     *
     * Returns `null` if [amount] is non-positive or nothing remains.
     */
    fun utilize(
        amount: Money,
        cashAccountId: AccountId,
        provisionAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (amount.amount.signum() <= 0) return null
        if (balance.amount.signum() <= 0) return null
        val settled = if (amount > balance) balance else amount

        balance = balance - settled

        val lines = listOf(
            JournalLine(provisionAccountId, settled, TransactionSide.DEBIT),
            JournalLine(
                cashAccountId, settled, TransactionSide.CREDIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
            )
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.MANUAL,
            "Provision utilized - $description ($id)", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            description: String,
            currency: Currency,
            id: ProvisionId = ProvisionId.generate()
        ): Provision = Provision(id, companyId, description, currency)

        /**
         * Rebuilds an already-valid Provision from persisted state
         * (docs/DDD_Design.md Section 10.18) - `internal`, matches the
         * repository-only visibility precedent every other aggregate's
         * `reconstitute()` already uses. Currently only called from
         * `LeaveAccrual.reconstitute()` - Provision has no repository of
         * its own (confirmed 2026-08-24: it's only ever used as
         * `LeaveAccrual`'s private embedded delegate, so its persisted
         * state is inlined into `leave_accruals` rather than getting a
         * standalone `provisions` table).
         */
        internal fun reconstitute(
            id: ProvisionId,
            companyId: CompanyId,
            description: String,
            currency: Currency,
            balance: Money
        ): Provision {
            val provision = Provision(id, companyId, description, currency)
            provision.balance = balance
            return provision
        }
    }
}
