package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.AgingBucketLabel
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/**
 * A party the Company sells to - the subsidiary-ledger side of the
 * Accounts Receivable control account (docs/DDD_Design.md Section 2.5).
 * Structurally the mirror of `Creditor` (`domain.purchasing`), same
 * shape, different direction: [recordSale] increases [balance] (a debit
 * to AR, per the control-account mechanism), [recordReceipt] decreases
 * it.
 *
 * Registration is unconditional here too - the "only customers with
 * outstanding payments need to be registered" framing describes when a
 * Customer is meaningfully a *Debtor* (balance > 0), not a gate on
 * creating the Customer record itself.
 */
class Customer private constructor(
    val id: CustomerId,
    val companyId: CompanyId,
    val name: String,
    val currency: Currency
) {
    var balance: Money = Money(BigDecimal.ZERO, currency)
        private set

    /** IFRS 9 Expected Credit Loss provision - a contra-asset against [balance], not a reduction of it. */
    var allowanceForExpectedCreditLoss: Money = Money(BigDecimal.ZERO, currency)
        private set

    val netReceivable: Money
        get() = balance - allowanceForExpectedCreditLoss

    /** Called when a SalesOrder line is delivered - AR/income recognized at that point, not at order placement. */
    fun recordSale(amount: Money): ValidationResult {
        if (amount.amount.signum() <= 0) {
            return ValidationResult.failure("Sale amount must be positive")
        }
        balance = balance + amount
        return ValidationResult.success()
    }

    /** Called when this Customer pays. */
    fun recordReceipt(amount: Money): ValidationResult {
        if (amount.amount.signum() <= 0) {
            return ValidationResult.failure("Receipt amount must be positive")
        }
        balance = balance - amount
        return ValidationResult.success()
    }

    /**
     * Records a payment from this Customer AND posts the corresponding
     * `JournalEntry` (debit Cash, credit the AR control account) in the
     * same call - control account and subsidiary ledger move together
     * by construction, same pattern as `SalesOrder.deliverLine()`.
     *
     * Fixes a real gap found 2026-08-12: [recordReceipt] existed with no
     * caller anywhere that actually posted the offsetting entry, meaning
     * a payment updated `balance` but left no trace in the Ledger - which
     * would make any aging/reporting derived from posted `JournalEntry`
     * data (e.g. `AccountsReceivableAging`) wrong, since every past sale would
     * look permanently unpaid. The AR line is tagged `DimensionType.CUSTOMER`,
     * same as the sale side. The cash line is tagged
     * `CashFlowActivity.OPERATING` (IAS 7) - collecting a receivable is
     * always an Operating activity, unlike `FixedAsset.dispose()`'s
     * proceeds, which are Investing.
     *
     * Returns `null` (not a `ValidationResult`) if the amount is
     * non-positive, matching `SalesOrder.deliverLine()`'s precedent for
     * a method producing a new object only on success.
     */
    fun receivePayment(
        amount: Money,
        cashAccountId: AccountId,
        arControlAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (!recordReceipt(amount).isValid) return null

        val lines = listOf(
            JournalLine(
                cashAccountId, amount, TransactionSide.DEBIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
            ),
            JournalLine(
                arControlAccountId, amount, TransactionSide.CREDIT,
                mapOf(DimensionType.CUSTOMER to id.value.toString())
            )
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.MANUAL,
            "Payment received - $name", journalEntryId
        )
    }

    /**
     * IFRS 9's simplified approach for trade receivables: always measure
     * at lifetime Expected Credit Loss via a provision matrix, not the
     * full 3-stage general model. [aging] supplies the bucketed
     * outstanding amounts (see `AccountsReceivableAging`, derived from the
     * Ledger); [lossRates] is the loss-rate-per-bucket input - "data,
     * not code," the same treatment already used for `TaxRule`/
     * `PayrollDeductionLine`. A bucket missing from [lossRates] defaults
     * to a zero rate rather than failing.
     *
     * Re-assesses to a **target** allowance each call, rather than
     * accumulating like `FixedAsset.recordDepreciation()` - unlike
     * depreciation, ECL isn't a period cost that only grows; if the
     * balance shrinks or loss rates improve, the allowance should move
     * down too. Posts only the **delta** between the new target and the
     * current allowance: a top-up (debit expense/credit allowance) if
     * the target increased, a reversal (debit allowance/credit expense)
     * if it decreased. The target is capped at [balance] - never
     * provision more than what's actually owed.
     *
     * Returns `null` (not a `ValidationResult`) if [aging] doesn't
     * belong to this Customer, or if the delta is exactly zero (nothing
     * to post) - matches `PurchaseOrder.send()`'s precedent for a
     * mismatched-counterparty check, and `FixedAsset.recordDepreciation()`'s
     * precedent for "nothing changed, nothing to post."
     */
    fun assessExpectedCreditLoss(
        aging: AccountsReceivableAging,
        lossRates: Map<AgingBucketLabel, BigDecimal>,
        expenseAccountId: AccountId,
        allowanceAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (aging.customerId != id) return null

        val zero = Money(BigDecimal.ZERO, currency)
        val targetAllowance = aging.buckets.fold(zero) { sum, bucket ->
            sum + (bucket.amount * (lossRates[bucket.label] ?: BigDecimal.ZERO))
        }
        val cappedTarget = if (targetAllowance > balance) balance else targetAllowance

        val delta = cappedTarget - allowanceForExpectedCreditLoss
        if (delta.amount.signum() == 0) return null

        val lines = if (delta.amount.signum() > 0) {
            listOf(
                JournalLine(expenseAccountId, delta, TransactionSide.DEBIT),
                JournalLine(allowanceAccountId, delta, TransactionSide.CREDIT)
            )
        } else {
            val reversalAmount = zero - delta
            listOf(
                JournalLine(allowanceAccountId, reversalAmount, TransactionSide.DEBIT),
                JournalLine(expenseAccountId, reversalAmount, TransactionSide.CREDIT)
            )
        }

        allowanceForExpectedCreditLoss = cappedTarget
        return JournalEntry.create(
            periodId, date, lines, JournalSource.SYSTEM,
            "Expected credit loss assessment - $name", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            name: String,
            currency: Currency,
            id: CustomerId = CustomerId.generate()
        ): Customer = Customer(id, companyId, name, currency)
    }
}
