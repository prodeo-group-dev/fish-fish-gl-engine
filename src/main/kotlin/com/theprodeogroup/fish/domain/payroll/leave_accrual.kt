package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.ledger.Provision
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.time.LocalDate
import java.util.Currency

/**
 * IAS 19's accumulating compensated absences (docs/DDD_Design.md
 * Section 2.7/2.1) - paid leave (holiday/vacation) that carries forward
 * if unused, so the expected cost must be accrued as the employee earns
 * entitlement, not just recognized when the leave is taken.
 *
 * A thin, [EmployeeId]-scoped wrapper over `domain.ledger.Provision`,
 * not a new posting mechanism: IAS 19's accumulating-absence liability
 * is structurally identical to an IAS 37 Provision (an uncertain-amount
 * liability, re-measured to a target each period, drawn down when
 * settled). What this type adds is discoverability from the Payroll
 * side - an `employeeId` field and Payroll-appropriate method names -
 * rather than a bare `Provision` with a free-text description.
 *
 * **Not for non-accumulating absences** (leave that lapses if unused,
 * e.g. non-rolling sick leave) - IAS 19.13 requires *no* liability or
 * expense until that leave is actually taken, since rendering service
 * doesn't increase the benefit. Those are posted as an ordinary
 * `Payslip` line; there's no accrual concept for them at all, and this
 * type shouldn't be used for that case.
 *
 * **Bonus/profit-share accruals use `domain.ledger.AccruedExpense`
 * directly**, not this type - IAS 19.19's recognition criteria (present
 * obligation, reliable estimate) is the same shape as an ordinary
 * accrued expense, with no Payroll-specific wrapper needed.
 *
 * **Avoiding double-counting between [utilizeLeave] and `PayRun` is
 * the caller's responsibility, not this type's.** When accrued leave is
 * taken, the employee is still paid through the normal pay run - but
 * that portion of pay must be charged against [utilizeLeave] (drawing
 * down the already-accrued liability), not folded into `PayRun.totalWages`/
 * `totalSalaries` again, or the cost is recognized twice: once as it
 * accrued, again as "worked" pay. Splitting a pay period's money into
 * worked vs. leave-funded portions is HR/Payroll's own concern - the
 * Ledger only needs the money amount for each, not which employee or
 * calendar days were on leave.
 */
class LeaveAccrual private constructor(
    val id: LeaveAccrualId,
    val companyId: CompanyId,
    val employeeId: EmployeeId,
    private val provision: Provision
) {
    val balance: Money
        get() = provision.balance

    /**
     * Re-measures the accrued leave liability to [targetAmount] - the
     * expected cost of the employee's unused entitlement (IAS 19.19),
     * however that's estimated (days accumulated x daily rate, adjusted
     * for vesting/non-vesting probability of use) is a caller-supplied
     * input, same "data, not code" treatment `Provision` itself already
     * uses. Delegates fully to `Provision.remeasure()` - see there for
     * the posting behavior (delta-only, top-up or reversal,
     * `JournalSource.SYSTEM`).
     */
    fun remeasure(
        targetAmount: Money,
        leaveExpenseAccountId: AccountId,
        accruedLeaveLiabilityAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? = provision.remeasure(
        targetAmount, leaveExpenseAccountId, accruedLeaveLiabilityAccountId, periodId, date, journalEntryId
    )

    /**
     * Draws down the accrued liability when leave is taken and paid -
     * the money value of the leave taken, not itself a `Payslip` line.
     * Delegates fully to `Provision.utilize()` - see there for the
     * posting behavior (capped at [balance], `CashFlowActivity.OPERATING`).
     */
    fun utilizeLeave(
        amount: Money,
        cashAccountId: AccountId,
        accruedLeaveLiabilityAccountId: AccountId,
        periodId: PeriodId,
        date: LocalDate,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry? = provision.utilize(
        amount, cashAccountId, accruedLeaveLiabilityAccountId, periodId, date, journalEntryId
    )

    companion object {
        fun create(
            companyId: CompanyId,
            employeeId: EmployeeId,
            currency: Currency,
            id: LeaveAccrualId = LeaveAccrualId.generate()
        ): LeaveAccrual = LeaveAccrual(
            id, companyId, employeeId,
            Provision.create(companyId, "Accrued leave - $employeeId", currency)
        )
    }
}
