package com.theprodeogroup.fish.domain.payroll

import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.common.JournalSource
import com.theprodeogroup.fish.domain.common.TransactionSide
import com.theprodeogroup.fish.domain.ledger.AccountId
import com.theprodeogroup.fish.domain.ledger.CashFlowActivity
import com.theprodeogroup.fish.domain.ledger.JournalEntry
import com.theprodeogroup.fish.domain.ledger.JournalEntryId
import com.theprodeogroup.fish.domain.ledger.JournalLine
import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.ledger.PeriodId
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The GL Engine's posting interface for a payroll run - **replaces**
 * `Payslip`'s earlier per-employee, per-deduction-line detail
 * (docs/DDD_Design.md Section 2.7), confirmed 2026-08-12 once HR/Payroll
 * was established as a genuinely separate system from the GL Engine,
 * the same relationship Lending/Scrip/Osusu already have.
 *
 * Individual employee gross pay, PAYE/NI/pension deductions, and
 * employer costs are HR/Payroll's own internal operational detail now -
 * exactly what the "financial effect only, never operational detail"
 * boundary (Section 2.1) says shouldn't cross into the Ledger. All the
 * GL Engine needs from a pay run is its net financial effect: total
 * wages and salaries incurred, and the cash paid out. No `Employee`
 * reference at all - a pay run's effect is a company-level total, not a
 * per-employee breakdown.
 *
 * [totalWages] (production/direct labor, conventionally debited within
 * a Trading Account) and [totalSalaries] (administrative/indirect
 * labor, conventionally debited within a Profit & Loss Account) post to
 * whichever `AccountId` the caller supplies for each - same "just the
 * posting, no new classification" treatment already used for COGS
 * (`SalesOrder.deliverLine()`'s `cogsExpenseAccountId`). Which section
 * of an eventual two-stage Trading & P&L statement an Expense account
 * belongs to is a chart-of-accounts/reporting concern; `ProfitAndLoss`
 * doesn't compute a separate Gross Profit stage yet, and this type
 * doesn't introduce one either - deliberately deferred, not an oversight.
 *
 * Both post straight to [cashAccountId] - no Net Pay Payable
 * intermediate step, matching the confirmed framing ("crediting it to
 * cash or bank account during the pay run"). Deductions and employer
 * costs (PAYE, NI, pension) are HR/Payroll's own settlement, entirely
 * outside this posting - if HR/Payroll needs to remit those, that's its
 * own system's concern, not a GL Engine journal entry.
 *
 * Either [totalWages] or [totalSalaries] may be zero (an all-salaried
 * office with no production wages, or vice versa) - the zero-amount
 * side is omitted from the posting entirely, matching the "nothing to
 * post" precedent used throughout this codebase (`Payslip.post()`'s
 * omitted net-pay line, `FixedAsset.dispose()`'s omitted zero legs).
 * Both cannot be zero - validated at [create].
 */
class PayRun private constructor(
    val id: PayRunId,
    val companyId: CompanyId,
    val date: LocalDate,
    val totalWages: Money,
    val totalSalaries: Money
) {
    /**
     * Debits [wagesExpenseAccountId] for [totalWages] and
     * [salariesExpenseAccountId] for [totalSalaries] (whichever is
     * positive), credits [cashAccountId] for their sum. The cash line is
     * tagged `CashFlowActivity.OPERATING` (IAS 7 - paying wages and
     * salaries is an ordinary Operating outflow).
     *
     * `JournalSource.INTEGRATION`, not `MANUAL` - this is posted by an
     * external system (HR/Payroll), matching that enum's own "Xero
     * accounting sync, bank feed integration" examples, not a person
     * typing directly into FiSH.
     *
     * Returns `JournalEntry` directly, not nullable - `PayRun` has no
     * state machine to check at posting time, every validity check
     * already happened at [create], matching `CashBookEntry.toJournalEntry()`'s
     * precedent for an aggregate with no lifecycle.
     */
    fun post(
        wagesExpenseAccountId: AccountId,
        salariesExpenseAccountId: AccountId,
        cashAccountId: AccountId,
        periodId: PeriodId,
        journalEntryId: JournalEntryId = JournalEntryId.generate()
    ): JournalEntry {
        val lines = mutableListOf<JournalLine>()
        var totalCash = Money(BigDecimal.ZERO, totalWages.currency)

        if (totalWages.amount.signum() > 0) {
            lines.add(JournalLine(wagesExpenseAccountId, totalWages, TransactionSide.DEBIT))
            totalCash += totalWages
        }
        if (totalSalaries.amount.signum() > 0) {
            lines.add(JournalLine(salariesExpenseAccountId, totalSalaries, TransactionSide.DEBIT))
            totalCash += totalSalaries
        }
        lines.add(
            JournalLine(
                cashAccountId, totalCash, TransactionSide.CREDIT,
                mapOf(DimensionType.CASH_FLOW_ACTIVITY to CashFlowActivity.OPERATING.name)
            )
        )

        return JournalEntry.create(
            periodId, date, lines, JournalSource.INTEGRATION,
            "Pay run - $id", journalEntryId
        )
    }

    companion object {
        fun create(
            companyId: CompanyId,
            date: LocalDate,
            totalWages: Money,
            totalSalaries: Money,
            id: PayRunId = PayRunId.generate()
        ): PayRun {
            require(totalWages.currency == totalSalaries.currency) {
                "Total wages and total salaries must be in the same currency"
            }
            require(totalWages.amount.signum() >= 0) { "Total wages cannot be negative" }
            require(totalSalaries.amount.signum() >= 0) { "Total salaries cannot be negative" }
            require(totalWages.amount.signum() > 0 || totalSalaries.amount.signum() > 0) {
                "A pay run must have positive total wages or total salaries (or both)"
            }
            return PayRun(id, companyId, date, totalWages, totalSalaries)
        }
    }
}
