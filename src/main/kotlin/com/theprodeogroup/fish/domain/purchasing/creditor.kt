package com.theprodeogroup.fish.domain.purchasing

import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
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

    companion object {
        fun create(
            companyId: CompanyId,
            name: String,
            currency: Currency,
            id: CreditorId = CreditorId.generate()
        ): Creditor = Creditor(id, companyId, name, currency)
    }
}
