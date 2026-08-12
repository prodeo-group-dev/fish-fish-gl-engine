package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.Money

/**
 * One deduction from an employee's gross pay - e.g. PAYE, National
 * Insurance, pension, salary-advance recovery. Deliberately generic
 * (a description and the liability `Account` it's credited to), not a
 * jurisdiction-specific PAYE/NI calculator - the "data, not code"
 * treatment already flagged for `TaxRule` (docs/DDD_Design.md Section
 * 2.7), confirmed for Payroll too before this increment was built.
 */
data class PayrollDeductionLine(
    val description: String,
    val payableAccountId: AccountId,
    val amount: Money
)

/**
 * An employer-side cost that isn't deducted from the employee's pay -
 * e.g. employer National Insurance, pension contribution. Unlike
 * [PayrollDeductionLine], this is its own self-balancing debit/credit
 * pair (an expense the employer incurs, and the liability it owes),
 * not a share of the employee's gross pay.
 */
data class EmployerCostLine(
    val description: String,
    val expenseAccountId: AccountId,
    val payableAccountId: AccountId,
    val amount: Money
)
