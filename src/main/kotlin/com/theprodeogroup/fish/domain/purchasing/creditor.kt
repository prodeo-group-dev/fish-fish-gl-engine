package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.ledger.AccountId
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
 * A supplier the Company owes money to - the subsidiary-ledger side of
 * the Accounts Payable control account (docs/DDD_Design.md Section 2.5).
 * Its [balance] must always tally with the sum of every Creditor's
 * balance against the `AccountsPayable` control `Account` in Ledger -
 * that reconciliation is a reporting concern (like the Trial Balance,
 * Section 3.1), not enforced here; this just keeps its own side correct.
 *
 * Registration is unconditional here (unlike Debtors, Section 2.5's
 * "only customers with outstanding payments need to be registered"
 * rule) - creditors don't have the cash-supplier/loyalty-scheme
 * asymmetry that motivated that rule on the Debtor side.
 */
class Creditor private constructor(
    val id: CreditorId,
    val companyId: CompanyId,
    val name: String,
    val currency: Currency
) {
    var balance: Money = Money(BigDecimal.ZERO, currency)
        private set

    /** Called when a PurchaseOrder is sent - AP recognized in full at that point (Section 2.5). */
    fun recordCharge(amount: Money): ValidationResult {
        if (amount.amount.signum() <= 0) {
            return ValidationResult.failure("Charge amount must be positive")
        }
        balance = balance + amount
        return ValidationResult.success()
    }

    /** Called when this Creditor is paid. */
    fun recordPayment(amount: Money): ValidationResult {
        if (amount.amount.signum() <= 0) {
            return ValidationResult.failure("Payment amount must be positive")
        }
        balance = balance - amount
        return ValidationResult.success()
    }

    /**
     * Records a payment to this Creditor AND posts the corresponding
     * `JournalEntry` (debit the AP control account, credit Cash) in the
     * same call - mirrors `Customer.receivePayment()`'s fix for the
     * identical gap on the AR side (2026-08-12): [recordPayment] existed
     * with no caller anywhere that posted the offsetting entry, which
     * would make any aging derived from posted `JournalEntry` data wrong
     * (every past charge would look permanently unpaid). The AP line is
     * tagged `DimensionType.VENDOR`, same as the charge side. The cash
     * line is tagged `CashFlowActivity.OPERATING` (IAS 7) - settling a
     * payable is always an Operating activity.
     *
     * Debit, not credit, on the AP control line - the mirror image of
     * `PurchaseOrder.send()`'s credit: a payment *decreases* a liability.
     *
     * Returns `null` (not a `ValidationResult`) if the amount is
     * non-positive, matching `Customer.receivePayment()`'s precedent.
     */
    fun makePayment(
        amount: Money,
        cashAccountId: AccountId,
        apControlAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? {
        if (!recordPayment(amount).isValid) return null

        val lines = listOf(
            JournalLine(
                apControlAccountId, amount, TransactionSide.DEBIT,
                mapOf(DimensionType.VENDOR to id.value.toString())
            ),
            JournalLine(
                cashAccountId, amount, TransactionSide.CREDIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
            )
        )
        return JournalEntry.create(
            periodId, date, lines, JournalSource.MANUAL,
            "Payment made - $name", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            name: String,
            currency: Currency,
            id: CreditorId = CreditorId.generate()
        ): Creditor = Creditor(id, companyId, name, currency)

        /**
         * Rebuilds an already-valid Creditor from persisted state
         * (docs/DDD_Design.md Section 10.4) - `internal`, matches the
         * repository-only visibility of every other aggregate's
         * `reconstitute()`.
         */
        internal fun reconstitute(
            id: CreditorId,
            companyId: CompanyId,
            name: String,
            currency: Currency,
            balance: Money
        ): Creditor {
            val creditor = Creditor(id, companyId, name, currency)
            creditor.balance = balance
            return creditor
        }
    }
}
