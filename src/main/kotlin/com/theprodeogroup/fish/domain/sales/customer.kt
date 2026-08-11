package com.theprodeogroup.fish.domain.sales

import com.theprodeogroup.fish.domain.common.ValidationResult
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
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

    companion object {
        fun create(
            companyId: CompanyId,
            name: String,
            currency: Currency,
            id: CustomerId = CustomerId.generate()
        ): Customer = Customer(id, companyId, name, currency)
    }
}
