package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * One employee's pay for a pay run (docs/DDD_Design.md Section 2.7) -
 * Increment 4 of the ecosystem, built to the scope confirmed before
 * building (park-don't-guess, matching Increment 3's precedent):
 * generic named [PayrollDeductionLine]s rather than a jurisdiction-
 * specific PAYE/NI calculator (the "data, not code" treatment already
 * flagged for `TaxRule`), and no `Employee` master-data aggregate or
 * salary-advances mechanism yet - both deliberately deferred.
 *
 * No state machine (unlike `PurchaseOrder`/`SalesOrder`) - a Payslip's
 * validity is fully determined at construction, so [post] always
 * succeeds and returns a `JournalEntry` directly, matching
 * `CashBookEntry.toJournalEntry()`'s precedent for an aggregate with no
 * lifecycle to check at posting time.
 */
class Payslip private constructor(
    val id: PayslipId,
    val companyId: CompanyId,
    val employeeId: EmployeeId,
    val date: LocalDate,
    val grossPay: Money,
    val earningsAccountId: AccountId,
    val netPayAccountId: AccountId,
    val deductions: List<PayrollDeductionLine>,
    val employerCosts: List<EmployerCostLine>
) {
    val totalDeductions: Money
        get() = deductions.map { it.amount }
            .fold(Money(BigDecimal.ZERO, grossPay.currency)) { sum, amount -> sum + amount }

    val netPay: Money
        get() = grossPay - totalDeductions

    /**
     * Debits [earningsAccountId] for the full gross pay, credits each
     * deduction's payable account, and credits [netPayAccountId] for
     * what's left - the standard payroll double entry. Each
     * [EmployerCostLine] is its own self-balancing debit/credit pair
     * (expense/payable), added independently rather than folded into
     * the gross-pay split, since it's not part of what the employee
     * is owed.
     */
    fun post(
        periodId: PeriodId,
        now: Instant = Instant.now(),
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry {
        val lines = mutableListOf(
            JournalLine(earningsAccountId, grossPay, TransactionSide.DEBIT)
        )
        deductions.forEach { deduction ->
            lines.add(JournalLine(deduction.payableAccountId, deduction.amount, TransactionSide.CREDIT))
        }
        if (netPay.amount.signum() > 0) {
            lines.add(JournalLine(netPayAccountId, netPay, TransactionSide.CREDIT))
        }
        employerCosts.forEach { cost ->
            lines.add(JournalLine(cost.expenseAccountId, cost.amount, TransactionSide.DEBIT))
            lines.add(JournalLine(cost.payableAccountId, cost.amount, TransactionSide.CREDIT))
        }
        return JournalEntry.create(
            periodId, date, lines, JournalSource.MANUAL,
            "Payslip $id - employee $employeeId", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            employeeId: EmployeeId,
            date: LocalDate,
            grossPay: Money,
            earningsAccountId: AccountId,
            netPayAccountId: AccountId,
            deductions: List<PayrollDeductionLine> = emptyList(),
            employerCosts: List<EmployerCostLine> = emptyList(),
            id: PayslipId = PayslipId.generate()
        ): Payslip {
            require(grossPay.amount.signum() > 0) { "Gross pay must be positive" }
            require(deductions.all { it.amount.currency == grossPay.currency }) {
                "All deductions must be in the same currency as gross pay"
            }
            require(employerCosts.all { it.amount.currency == grossPay.currency }) {
                "All employer costs must be in the same currency as gross pay"
            }
            val totalDeductions = deductions.map { it.amount }
                .fold(Money(BigDecimal.ZERO, grossPay.currency)) { sum, amount -> sum + amount }
            require(totalDeductions <= grossPay) {
                "Total deductions ($totalDeductions) cannot exceed gross pay ($grossPay)"
            }
            return Payslip(
                id, companyId, employeeId, date, grossPay,
                earningsAccountId, netPayAccountId, deductions, employerCosts
            )
        }
    }
}
