package com.theprodeogroup.fish.domain.ledger

import com.theprodeogroup.common.Money
import com.theprodeogroup.fish.domain.common.DimensionType
import com.theprodeogroup.fish.domain.tenancy.CompanyId
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Currency

/** One IAS 7 activity category's net cash movement over the statement's date range. Signed - positive is a net inflow. */
data class CashFlowActivityAmount(
    val activity: CashFlowActivity,
    val netAmount: Money
)

/**
 * IAS 7's full requirement - reconciling a cash/bank Account's opening
 * balance to its closing balance over a date range, AND categorizing the
 * movement into Operating/Investing/Financing activities
 * (docs/DDD_Design.md Section 2.1). A query/report layer over
 * already-posted `JournalEntry` data, same shape as `TrialBalance`/
 * `WorkingCapital`.
 *
 * **Categorization reads the `DimensionType.CASH_FLOW_ACTIVITY` tag off
 * the cash/bank `JournalLine` itself**, not derived from the counter-
 * account - confirmed 2026-08-12, after the originally-recommended
 * "derive from counter-account type" heuristic was traced against real
 * entries and found to break two ways (see git history / prior design
 * note): `FixedAsset.dispose()`'s cash line has a Revenue-typed
 * counter-account but is conceptually Investing, and compound multi-line
 * entries have no single well-defined "the counter-account." Tagging the
 * cash line directly, in the method that actually knows the transaction's
 * economic substance (`Customer.receivePayment()`/`Creditor.makePayment()`
 * → OPERATING, `FixedAsset.dispose()` → INVESTING,
 * `CashBookEntry.cashFlowActivity` → caller-supplied, defaulting to
 * OPERATING), sidesteps both problems entirely.
 *
 * A cash line with no `CASH_FLOW_ACTIVITY` tag (an older entry predating
 * this retrofit, or a posting method not yet updated - e.g. Payroll/
 * Prepayment don't touch cash directly today, so weren't retrofitted)
 * falls into [uncategorizedAmount] rather than being silently dropped or
 * crashing - [activityAmounts] plus [uncategorizedAmount] always sums to
 * [netCashFlow].
 */
class StatementOfCashFlows private constructor(
    val companyId: CompanyId,
    val cashAccountId: AccountId,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val currency: Currency,
    val openingBalance: Money,
    val closingBalance: Money,
    val activityAmounts: List<CashFlowActivityAmount>,
    val uncategorizedAmount: Money
) {
    val netCashFlow: Money
        get() = closingBalance - openingBalance

    companion object {
        fun of(
            cashAccount: Account,
            postedEntries: List<JournalEntry>,
            startDate: LocalDate,
            endDate: LocalDate,
            currency: Currency
        ): StatementOfCashFlows {
            require(!endDate.isBefore(startDate)) { "endDate cannot be before startDate" }

            val openingEntries = postedEntries.filter { it.date.isBefore(startDate) }
            val closingEntries = postedEntries.filter { !it.date.isAfter(endDate) }
            val periodEntries = postedEntries.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }

            val openingBalance = accountBalances(listOf(cashAccount), openingEntries, currency).getValue(cashAccount.id)
            val closingBalance = accountBalances(listOf(cashAccount), closingEntries, currency).getValue(cashAccount.id)

            val zero = Money(BigDecimal.ZERO, currency)
            val activityTotals = CashFlowActivity.entries.associateWith { zero }.toMutableMap()
            var uncategorized = zero

            periodEntries
                .filter { it.status.hasHistoricalEffect() }
                .flatMap { it.lines }
                .filter { it.accountId == cashAccount.id }
                .forEach { line ->
                    val signedAmount = if (line.side == cashAccount.type.normalBalance()) line.amount else zero - line.amount
                    val activity = line.dimensions[DimensionType.CASH_FLOW_ACTIVITY]
                        ?.let { runCatching { CashFlowActivity.valueOf(it) }.getOrNull() }
                    if (activity != null) {
                        activityTotals[activity] = activityTotals.getValue(activity) + signedAmount
                    } else {
                        uncategorized = uncategorized + signedAmount
                    }
                }

            val activityAmounts = CashFlowActivity.entries.map {
                CashFlowActivityAmount(it, activityTotals.getValue(it))
            }

            return StatementOfCashFlows(
                cashAccount.companyId, cashAccount.id, startDate, endDate, currency,
                openingBalance, closingBalance, activityAmounts, uncategorized
            )
        }
    }
}
